/*
 * Copyright (C) 2014 Andrew Comminos
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.morlunk.jumble.audio;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.util.Log;

import com.googlecode.javacpp.IntPointer;
import com.googlecode.javacpp.Loader;
import com.morlunk.jumble.Constants;
import com.morlunk.jumble.audio.javacpp.CELT11;
import com.morlunk.jumble.audio.javacpp.CELT7;
import com.morlunk.jumble.audio.javacpp.Opus;
import com.morlunk.jumble.audio.javacpp.Speex;
import com.morlunk.jumble.exception.AudioInitializationException;
import com.morlunk.jumble.exception.NativeAudioException;
import com.morlunk.jumble.net.JumbleUDPMessageType;
import com.morlunk.jumble.net.PacketDataStream;
import com.morlunk.jumble.protocol.AudioHandler;

/**
 * Created by andrew on 23/08/13.
 */
public class AudioInput implements Runnable {

    static {
        Loader.load(Opus.class); // Do this so we can reference IntPointer and the like earlier.
    }

    public interface AudioInputListener {
        /**
         * Called when a frame has finished processing, and is ready to go to the server.
         * @param data The encoded audio data.
         * @param length The length of the encoded audio data.
         * @param messageType The codec of the encoded data.
         */
        public void onFrameEncoded(byte[] data, int length, JumbleUDPMessageType messageType);

        public void onTalkStateChanged(boolean talking);
    }

    public static final int[] SAMPLE_RATES = { 48000, 44100, 22050, 16000, 11025, 8000 };
    private static final int SPEEX_RESAMPLE_QUALITY = 3;
    private static final int OPUS_MAX_BYTES = 960; // Opus specifies 4000 bytes as a recommended value for encoding, but the official mumble project uses 512.
    private static final int SPEECH_DETECT_THRESHOLD = (int) (0.25 * Math.pow(10, 9)); // Continue speech for 250ms to prevent dropping

    private IEncoder mEncoder;
    private Speex.SpeexPreprocessState mPreprocessState;
    private Speex.SpeexResampler mResampler;

    // AudioRecord state
    private AudioInputListener mListener;
    private AudioRecord mAudioRecord;
    private int mSampleRate = -1;
    private int mFrameSize = AudioHandler.FRAME_SIZE;
    private int mMicFrameSize = AudioHandler.FRAME_SIZE;

    // Preferences
    private int mAudioSource;
    private int mBitrate;
    private final int mFramesPerPacket;
    private int mTransmitMode;
    private float mVADThreshold;
    private float mAmplitudeBoost = 1.0f;
    private boolean mUsePreprocessor = true;

    // Encoder state
    final short[] mAudioBuffer = new short[mFrameSize];
    final short[] mOpusBuffer;
    final byte[][] mCELTBuffer;
    short[] mResampleBuffer;

    private final byte[] mEncodedBuffer = new byte[OPUS_MAX_BYTES];
    private int mBufferedFrames = 0;
    private int mFrameCounter;

    private JumbleUDPMessageType mCodec = null;

    private Thread mRecordThread;
    private boolean mRecording;

    public AudioInput(AudioInputListener listener, JumbleUDPMessageType codec, int audioSource,
                      int targetSampleRate, int bitrate, int framesPerPacket, int transmitMode,
                      float vadThreshold, float amplitudeBoost, boolean preprocessorEnabled) throws NativeAudioException, AudioInitializationException {
        mListener = listener;
        mCodec = codec;
        mAudioSource = audioSource;
        mSampleRate = getSupportedSampleRate(targetSampleRate);
        mBitrate = bitrate;
        mFramesPerPacket = framesPerPacket;
        mTransmitMode = transmitMode;
        mVADThreshold = vadThreshold;
        mAmplitudeBoost = amplitudeBoost;
        mUsePreprocessor = preprocessorEnabled;

        mAudioRecord = createAudioRecord();
        mEncoder = createEncoder(mCodec);

        mOpusBuffer = new short[mFrameSize * mFramesPerPacket];
        mCELTBuffer = new byte[mFramesPerPacket][AudioHandler.SAMPLE_RATE / 800];

        if(mSampleRate != AudioHandler.SAMPLE_RATE) {
            mResampler = new Speex.SpeexResampler(1, mSampleRate, AudioHandler.SAMPLE_RATE, SPEEX_RESAMPLE_QUALITY);
            mMicFrameSize = (mSampleRate * mFrameSize) / AudioHandler.SAMPLE_RATE;
            mResampleBuffer = new short[mMicFrameSize];
        }

        configurePreprocessState();
    }

    /**
     * Checks if the preferred sample rate is supported, and use it if so. Otherwise, automatically find a supported rate.
     * @param preferredSampleRate The preferred sample rate.
     * @return The preferred sample rate if supported, otherwise the next best one.
     */
    private int getSupportedSampleRate(int preferredSampleRate) {
        // Attempt to use preferred rate first
        if(preferredSampleRate != -1) {
            int bufferSize = AudioRecord.getMinBufferSize(preferredSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if(bufferSize > 0) return preferredSampleRate;
        }

        // Use the highest sample rate we can get.
        for(int rate : SAMPLE_RATES) {
            if(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) > 0) {
                Log.w(Constants.TAG, "Failed to use desired sample rate, falling back to "+rate+"Hz.");
                return rate;
            }
        }

        // If all else fails, return the android default.
        return 48000;
    }

    /**
     * Initializes and configures the Speex preprocessor.
     * Based off of Mumble project's AudioInput method resetAudioProcessor().
     */
    private void configurePreprocessState() {
        if(mPreprocessState != null) mPreprocessState.destroy();

        mPreprocessState = new Speex.SpeexPreprocessState(mFrameSize, AudioHandler.SAMPLE_RATE);

        IntPointer arg = new IntPointer(1);

        arg.put(0);
        mPreprocessState.control(Speex.SpeexPreprocessState.SPEEX_PREPROCESS_SET_VAD, arg);
        arg.put(1);
        mPreprocessState.control(Speex.SpeexPreprocessState.SPEEX_PREPROCESS_SET_AGC, arg);
        mPreprocessState.control(Speex.SpeexPreprocessState.SPEEX_PREPROCESS_SET_DENOISE, arg);
        mPreprocessState.control(Speex.SpeexPreprocessState.SPEEX_PREPROCESS_SET_DEREVERB, arg);

        arg.put(30000);
        mPreprocessState.control(Speex.SpeexPreprocessState.SPEEX_PREPROCESS_SET_AGC_TARGET, arg);

        // TODO AGC max gain, decrement, noise suppress, echo

        // Increase VAD difficulty
        arg.put(99);
        mPreprocessState.control(Speex.SpeexPreprocessState.SPEEX_PREPROCESS_GET_PROB_START, arg);
    }

    private AudioRecord createAudioRecord() throws AudioInitializationException {
        int minBufferSize = AudioRecord.getMinBufferSize(mSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord audioRecord;
        try {
            audioRecord = new AudioRecord(mAudioSource, mSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufferSize);
        } catch (IllegalArgumentException e) {
            // This is almost always caused by an invalid sample rate specified.
            // Ideally, this should have been caught by checking for failed calls to getMinBufferSize with the
            // chosen sample rate. Unfortunately, some devices don't properly fail calling that method
            // when provided with an invalid buffer size.
            e.printStackTrace();
            if (mSampleRate != AudioHandler.SAMPLE_RATE) {
                Log.w(Constants.TAG, "Checks for input sample rate failed, defaulting to 48000hz");
                mSampleRate = AudioHandler.SAMPLE_RATE;
                return createAudioRecord();
            } else {
                throw new AudioInitializationException(e);
            }
        }

        if(audioRecord.getState() == AudioRecord.STATE_UNINITIALIZED) {
            throw new AudioInitializationException("AudioRecord failed to initialize!");
        }

        Log.i(Constants.TAG, "AudioInput: " + mBitrate + "bps, " + mFramesPerPacket + " frames/packet, " + mSampleRate + "hz");

        return audioRecord;
    }

    private IEncoder createEncoder(JumbleUDPMessageType codec) throws NativeAudioException {
        Log.v(Constants.TAG, "Using codec "+codec.toString()+" for input");

        IEncoder encoder;
        switch (codec) {
            case UDPVoiceOpus:
                encoder = new Opus.OpusEncoder(AudioHandler.SAMPLE_RATE, 1);
                break;
            case UDPVoiceCELTBeta:
                encoder = new CELT11.CELT11Encoder(AudioHandler.SAMPLE_RATE, 1);
                break;
            case UDPVoiceCELTAlpha:
                encoder = new CELT7.CELT7Encoder(AudioHandler.SAMPLE_RATE, mFrameSize, 1);
                break;
//            case UDPVoiceSpeex:
                // TODO
//                break;
            default:
                throw new NativeAudioException("Codec " + codec + " not supported.");
        }
        encoder.setBitrate(mBitrate);
        return encoder;
    }

    /**
     * Starts the recording thread.
     * Not thread-safe.
     */
    public void startRecording() {
        mRecording = true;
        mRecordThread = new Thread(this);
        mRecordThread.start();
    }

    /**
     * Stops the record loop after the current iteration.
     * Not thread-safe.
     */
    public void stopRecording() {
        if(!mRecording) return;
        mRecording = false;
        mRecordThread = null;
    }

    public void setVADThreshold(float threshold) {
        mVADThreshold = threshold;
    }

    public void setAmplitudeBoost(float boost) {
        if(boost < 0) throw new IllegalArgumentException("Amplitude boost must not be a negative number!");
        mAmplitudeBoost = boost;
    }

    public void setBitrate(int bitrate) {
        mBitrate = bitrate;
        if(mEncoder != null) mEncoder.setBitrate(bitrate);
    }

    public void setPreprocessorEnabled(boolean preprocessorEnabled) {
        mUsePreprocessor = preprocessorEnabled;
    }

    /**
     * Stops the record loop and waits on it to finish.
     * Releases native audio resources.
     * NOTE: It is safe to call startRecording after.
     * @throws InterruptedException
     */
    public void shutdown() {
        if(mRecording) {
            mRecording = false;
            try {
                mRecordThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        mRecordThread = null;
        mCodec = null;

        if(mAudioRecord != null) {
            mAudioRecord.release();
            mAudioRecord = null;
        }
        if(mEncoder != null) {
            mEncoder.destroy();
            mEncoder = null;
        }
        if(mPreprocessState != null) {
            mPreprocessState.destroy();
            mPreprocessState = null;
        }
        if(mResampler != null) {
            mResampler.destroy();
            mResampler = null;
        }
        if(mAudioRecord != null) {
            mAudioRecord.release();
            mAudioRecord = null;
        }
    }

    public boolean isRecording() {
        return mRecording;
    }

    @Override
    public void run() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

        if(mCodec == null) {
            Log.v(Constants.TAG, "Tried to start recording without a codec version!");
            return;
        }

        boolean vadLastDetected = false;
        long vadLastDetectedTime = 0;
        mBufferedFrames = 0;
        mFrameCounter = 0;

        mAudioRecord.startRecording();

        if(mAudioRecord.getState() != AudioRecord.STATE_INITIALIZED)
            return;

        if(mTransmitMode == Constants.TRANSMIT_CONTINUOUS || mTransmitMode == Constants.TRANSMIT_PUSH_TO_TALK)
            mListener.onTalkStateChanged(true);

        // We loop when the 'recording' instance var is true instead of checking audio record state because we want to always cleanly shutdown.
        while(mRecording) {
            int shortsRead = mAudioRecord.read(mResampler != null ? mResampleBuffer : mAudioBuffer, 0, mResampler != null ? mMicFrameSize : mFrameSize);
            if(shortsRead > 0) {
                int len = 0;
                boolean encoded = false;
                mFrameCounter++;

                // Resample if necessary
                if(mResampler != null) {
                    mResampler.resample(mResampleBuffer, mAudioBuffer);
                }

                // Run preprocessor on audio data. TODO echo!
                if (mUsePreprocessor) {
                    mPreprocessState.preprocess(mAudioBuffer);
                }

                // Boost/reduce amplitude based on user preference
                if(mAmplitudeBoost != 1.0f) {
                    for(int i = 0; i < mFrameSize; i++) {
                        mAudioBuffer[i] *= mAmplitudeBoost;
                        if(mAudioBuffer[i] > Short.MAX_VALUE) mAudioBuffer[i] = Short.MAX_VALUE;
                        else if(mAudioBuffer[i] < Short.MIN_VALUE) mAudioBuffer[i] = Short.MIN_VALUE;
                    }
                }

                boolean talking = true;

                if(mTransmitMode == Constants.TRANSMIT_VOICE_ACTIVITY) {
                    // Use a logarithmic energy-based scale for VAD.
                    float sum = 1.0f;
                    for (int i = 0; i < mFrameSize; i++) {
                        sum += mAudioBuffer[i] * mAudioBuffer[i];
                    }
                    float micLevel = (float) Math.sqrt(sum / (float)mFrameSize);
                    float peakSignal = (float) (20.0f*Math.log10(micLevel / 32768.0f))/96.0f;
                    talking = (peakSignal+1) >= mVADThreshold;

                    /* Record the last time where VAD was detected in order to prevent speech dropping. */
                    if(talking) vadLastDetectedTime = System.nanoTime();

//                    Log.v(Constants.TAG, String.format("Signal: %2f, Threshold: %2f", peakSignal+1, mVADThreshold));
                    talking |= (System.nanoTime() - vadLastDetectedTime) < SPEECH_DETECT_THRESHOLD;

                    if(talking ^ vadLastDetected) // Update the service with the new talking state if we detected voice.
                        mListener.onTalkStateChanged(talking);
                    vadLastDetected = talking;
                }

                if(!talking) {
                    continue;
                }

                // TODO integrate this switch's behaviour into IEncoder implementations
                switch (mCodec) {
                    case UDPVoiceOpus:
                        System.arraycopy(mAudioBuffer, 0, mOpusBuffer, mFrameSize * mBufferedFrames, mFrameSize);
                        mBufferedFrames++;

                        if((!mRecording && mBufferedFrames > 0) || mBufferedFrames >= mFramesPerPacket) {
                            if(mBufferedFrames < mFramesPerPacket)
                                mBufferedFrames = mFramesPerPacket; // If recording was stopped early, encode remaining empty frames too.

                            try {
                                len = mEncoder.encode(mOpusBuffer, mFrameSize * mBufferedFrames, mEncodedBuffer, OPUS_MAX_BYTES);
                                encoded = true;
                            } catch (NativeAudioException e) {
                                mBufferedFrames = 0;
                                continue;
                            }
                        }
                        break;
                    case UDPVoiceCELTBeta:
                    case UDPVoiceCELTAlpha:
                        try {
                            len = mEncoder.encode(mAudioBuffer, mFrameSize, mCELTBuffer[mBufferedFrames], AudioHandler.SAMPLE_RATE/800);
                            mBufferedFrames++;
                            encoded = mBufferedFrames >= mFramesPerPacket || (!mRecording && mBufferedFrames > 0);
                        } catch (NativeAudioException e) {
                            mBufferedFrames = 0;
                            continue;
                        }
                        break;
                    case UDPVoiceSpeex:
                        break;
                }

                if(encoded) sendFrame(!mRecording, len);
            } else {
                Log.e(Constants.TAG, "Error fetching audio! AudioRecord error "+shortsRead);
                mBufferedFrames = 0;
            }
        }

        mAudioRecord.stop();

        mListener.onTalkStateChanged(false);
    }

    /**
     * Sends the encoded frame to the server.
     * Volatile; depends on class state and must not be called concurrently.
     */
    private void sendFrame(boolean terminator, int length) {
        int frames = mBufferedFrames;
        mBufferedFrames = 0;

        int flags = 0;
        flags |= mCodec.ordinal() << 5;

        final byte[] packetBuffer = new byte[1024];
        packetBuffer[0] = (byte) (flags & 0xFF);

        PacketDataStream ds = new PacketDataStream(packetBuffer, 1024);
        ds.skip(1);
        ds.writeLong(mFrameCounter - frames);

        if(mCodec == JumbleUDPMessageType.UDPVoiceOpus) {
            byte[] frame = mEncodedBuffer;
            long size = length;
            if(terminator)
                size |= 1 << 13;
            ds.writeLong(size);
            ds.append(frame, length);
        } else {
            for (int x=0;x<frames;x++) {
                byte[] frame = mCELTBuffer[x];
                int head = frame.length;
                if(x < frames-1)
                   head |= 0x80;
                ds.append(head);
                ds.append(frame, frame.length);
            }
        }

        mListener.onFrameEncoded(packetBuffer, ds.size(), mCodec);
    }
}
