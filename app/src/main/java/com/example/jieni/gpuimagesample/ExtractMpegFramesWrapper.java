package com.example.jieni.gpuimagesample;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.opengl.GLSurfaceView;
import android.os.Environment;
import android.util.Log;
import android.view.SurfaceView;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;

import jp.co.cyberagent.android.gpuimage.GPUImage;
import jp.co.cyberagent.android.gpuimage.GPUImage.OnPictureSavedListener;
import jp.co.cyberagent.android.gpuimage.GPUImageFilter;
import jp.co.cyberagent.android.gpuimage.GPUImageGaussianBlurFilter;

/**
 * Created by jni on 11/3/2016.
 */
public class ExtractMpegFramesWrapper extends Thread{
    private final String TAG = "EMPWrapper";
    private final boolean VERBOSE = true;           // lots of logging

    private final boolean looping = true;

    private Throwable mThrowable;
    GLSurfaceView mGLSurfaceView;
    Context mContext;
    SurfaceView mSurfaceView;

    private GPUImage mGPUImage;
    private GPUImageFilter mFilter =    new GPUImageGaussianBlurFilter();


    // where to find files (note: requires WRITE_EXTERNAL_STORAGE permission)
    private final File FILES_DIR = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
    private final String INPUT_FILE = "source2.mp4";
    private final int MAX_FRAMES = 10000;       // stop extracting after this many

    public ExtractMpegFramesWrapper(GPUImage gpuImage, GLSurfaceView surfaceView, Context context){
        mGPUImage =gpuImage;
        mGLSurfaceView = surfaceView;
        mContext = context;

       // mGPUImage = new GPUImage(mContext);
        //mGPUImage.setGLSurfaceView((GLSurfaceView)mSurfaceView);
        //mGPUImage.setFilter(mFilter);
    }

    @Override
    public void run() {
        try {
            extractMpegFrames();
        } catch (Throwable th) {
            mThrowable = th;
        }
    }


    /**
     * We scale the video to 640x480 for the PNG just to demonstrate that we can scale the
     * video with the GPU.  If the input video has a different aspect ratio, we could preserve
     * it by adjusting the GL viewport to get letterboxing or pillarboxing, but generally if
     * you're extracting frames you don't want black bars.
     */
    private void extractMpegFrames() throws IOException {
        MediaCodec decoder = null;
        CodecOutputSurface outputSurface = null;
        MediaExtractor extractor = null;
        int videoWidth = 640;
        int videoHeight = 480;

        try {
            File inputFile = new File(FILES_DIR, INPUT_FILE);   // must be an absolute path
            if (!inputFile.canRead()) {
                Log.e(TAG, "Can not read the input file");
                throw new FileNotFoundException("Unable to read " + inputFile);
            }

            extractor = new MediaExtractor();
            extractor.setDataSource(inputFile.toString());
            int trackIndex = selectTrack(extractor);
            if (trackIndex < 0) {
                throw new RuntimeException("No video track found in " + inputFile);
            }
            extractor.selectTrack(trackIndex);

            MediaFormat format = extractor.getTrackFormat(trackIndex);
            if (VERBOSE) {
                Log.d(TAG, "Video size is " + format.getInteger(MediaFormat.KEY_WIDTH) + "x" +
                        format.getInteger(MediaFormat.KEY_HEIGHT));
            }
            videoWidth =format.getInteger(MediaFormat.KEY_WIDTH);
            videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
            // Could use width/height from the MediaFormat to get full-size frames.
            //outputSurface = new CodecOutputSurface(videoWidth, videoHeight);

            // Create a MediaCodec decoder, and configure it with the MediaFormat from the
            // extractor.  It's very important to use the format from the extractor because
            // it contains a copy of the CSD-0/CSD-1 codec-specific data chunks.
            String mime = format.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(mime);
            //decoder.configure(format, outputSurface.getSurface(), null, 0);
            decoder.configure(format, mGLSurfaceView.getHolder().getSurface(), null, 0);
            decoder.start();

            doExtract(extractor, trackIndex, decoder, outputSurface);
        } finally {
            // release everything we grabbed
            if (outputSurface != null) {
                outputSurface.release();
                outputSurface = null;
            }
            if (decoder != null) {
                decoder.stop();
                decoder.release();
                decoder = null;
            }
            if (extractor != null) {
                extractor.release();
                extractor = null;
            }
        }
    }

    /**
     * Selects the video track, if any.
     *
     * @return the track index, or -1 if no video track is found.
     */
    private int selectTrack(MediaExtractor extractor) {
        // Select the first video track we find, ignore the rest.
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime.startsWith("video/")) {
                if (VERBOSE) {
                    Log.d(TAG, "Extractor selected track " + i + " (" + mime + "): " + format);
                }
                return i;
            }
        }

        return -1;
    }

    /**
     * Work loop.
     */
    private void doExtract(MediaExtractor extractor, int trackIndex, MediaCodec decoder,
                          CodecOutputSurface outputSurface) throws IOException {
        final int TIMEOUT_USEC = 1000;
        ByteBuffer[] decoderInputBuffers = decoder.getInputBuffers();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int inputChunk = 0;
        int decodeCount = 0;
        long frameSaveTime = 0;

        boolean outputDone = false;
        boolean inputDone = false;
        while (!outputDone) {
            if (VERBOSE) Log.d(TAG, "loop");

            // Feed more data to the decoder.
            if (!inputDone) {
                int inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC);
                if (inputBufIndex >= 0) {
                    ByteBuffer inputBuf = decoderInputBuffers[inputBufIndex];
                    // Read the sample data into the ByteBuffer.  This neither respects nor
                    // updates inputBuf's position, limit, etc.
                    int chunkSize = extractor.readSampleData(inputBuf, 0);
                    if (chunkSize < 0) {
                        // End of stream -- send empty frame with EOS flag set.
                        decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                        if (VERBOSE) Log.d(TAG, "sent input EOS");
                    } else {
                        if (extractor.getSampleTrackIndex() != trackIndex) {
                            Log.w(TAG, "WEIRD: got sample from track " +
                                    extractor.getSampleTrackIndex() + ", expected " + trackIndex);
                        }
                        long presentationTimeUs = extractor.getSampleTime();
                        decoder.queueInputBuffer(inputBufIndex, 0, chunkSize,
                                presentationTimeUs, 0 /*flags*/);
                        if (VERBOSE) {
                            Log.d(TAG, "submitted frame " + inputChunk + " to dec, size=" +
                                    chunkSize);
                        }
                        inputChunk++;
                        extractor.advance();
                    }
                } else {
                    if (VERBOSE) Log.d(TAG, "input buffer not available");
                }
            }

            boolean doLoop = false;
            if (!outputDone) {
                int decoderStatus = decoder.dequeueOutputBuffer(info, TIMEOUT_USEC);
                if (decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // no output available yet
                    if (VERBOSE) Log.d(TAG, "no output from decoder available");
                } else if (decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    // not important for us, since we're using Surface
                    if (VERBOSE) Log.d(TAG, "decoder output buffers changed");
                } else if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = decoder.getOutputFormat();
                    if (VERBOSE) Log.d(TAG, "decoder output format changed: " + newFormat);
                } else if (decoderStatus < 0) {
                    Log.e(TAG, "unexpected result from decoder.dequeueOutputBuffer: " + decoderStatus);
                } else { // decoderStatus >= 0
                    if (VERBOSE) Log.d(TAG, "surface decoder given buffer " + decoderStatus +
                            " (size=" + info.size + ")");
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        if (VERBOSE) Log.d(TAG, "output EOS");

                        if(looping){
                            doLoop = true;
                        }else{
                            outputDone = true;
                        }

                    }

                    boolean doRender = (info.size != 0);

                    // As soon as we call releaseOutputBuffer, the buffer will be forwarded
                    // to SurfaceTexture to convert to a texture.  The API doesn't guarantee
                    // that the texture will be available before the call returns, so we
                    // need to wait for the onFrameAvailable callback to fire.
                    decoder.releaseOutputBuffer(decoderStatus, doRender);
                    if (doRender) {
                        if (VERBOSE) Log.d(TAG, "awaiting decode of frame " + decodeCount);
                        //outputSurface.awaitNewImage();
                        //outputSurface.drawImage(true);

                        //[NJ] Draw the captured frame to  the surface
                        //outputSurface.drawFrame(mSurfaceView,mGPUImage);

                    }

                    if (doLoop) {
                        Log.d(TAG, "Reached EOS, looping");
                        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                        inputDone = false;
                        decoder.flush();    // reset decoder state
                    }
                }
            }
        }

        int numSaved = (MAX_FRAMES < decodeCount) ? MAX_FRAMES : decodeCount;
        Log.d(TAG, "Saving " + numSaved + " frames took " +
                (frameSaveTime / numSaved / 1000) + " us per frame");
    }



}
