package com.example.jieni.gpuimagesample;

import android.opengl.GLSurfaceView;
import android.support.v4.app.Fragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;

import jp.co.cyberagent.android.gpuimage.GPUImage;

/**
 * A placeholder fragment containing a simple view.
 */
public class MainActivityFragment extends Fragment {

    private GPUImage mGPUImage;
    private SurfaceView mSurfaceView;
    private GLSurfaceView mglSurfaceView;
    public MainActivityFragment() {

    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_main, container, false);
        //mSurfaceView = (SurfaceView)view.findViewById(R.id.surfaceView);
        mglSurfaceView = (GLSurfaceView) view.findViewById(R.id.glSurfaceView);
        mGPUImage = new GPUImage(getActivity().getApplicationContext());
        mGPUImage.setGLSurfaceView(mglSurfaceView);
//        ExtractMpegFramesWrapper mpegFramesWrapper = new ExtractMpegFramesWrapper( null, mSurfaceView, getActivity().getApplicationContext());
        ExtractMpegFramesWrapper mpegFramesWrapper = new ExtractMpegFramesWrapper( mGPUImage, mglSurfaceView, getActivity().getApplicationContext());
        mpegFramesWrapper.start();
        return view;
    }
}
