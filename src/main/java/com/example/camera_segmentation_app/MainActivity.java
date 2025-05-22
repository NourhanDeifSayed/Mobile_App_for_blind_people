package com.example.camera_segmentation_app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

class OutSections {
    int topLeftx;
    int topLefty;
    int topRightx;
    int topRighty;
    int botLeftx;
    int botLefty;
    int botRightx;
    int botRighty;

    public OutSections(int topLeftx, int topLefty, int topRightx, int topRighty, int botLeftx, int botLefty, int botRightx, int botRighty) {
        this.topLeftx = topLeftx;
        this.topLefty = topLefty;
        this.topRightx = topRightx;
        this.topRighty = topRighty;
        this.botLeftx = botLeftx;
        this.botLefty = botLefty;
        this.botRightx = botRightx;
        this.botRighty = botRighty;
    }
}

public class MainActivity extends AppCompatActivity {

    private Bitmap mSegmImage;
    private TextToSpeech mTTs;

    private static final int REQUEST_CAMERA_PERMISSION_RESULT = 0;
    private TextureView mPreview;
    private ImageView mMiniView;
    private TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
            setupCamera(width, height);
            connectCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {

        }
    };

    private CameraDevice mCamera;
    private CameraDevice.StateCallback mCameraDeviceStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            mCamera = camera;
            startPreview();
            Toast.makeText(getApplicationContext(), "Camera connected successfully!", Toast.LENGTH_SHORT).show();

            final ImageSegmenter Segmenter = new ImageSegmenter(MainActivity.this);

            // Secondary thread to process the segmentation without blocking up the main.
            final Handler mHandler = new Handler(getMainLooper());
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    // Gets the preview image and places it in a bitmap object called
                    Bitmap fullimg = mPreview.getBitmap();
                    Bitmap img = Bitmap.createScaledBitmap(fullimg, 225, 225, true);

                    // Segmenter.segmentFrame(img)
                    // This is where the function to carry out the segmentation goes
                    int[] outputArray = Segmenter.segmentFrame(img);

                    Log.d("OUT", Arrays.toString(outputArray));

                    int width = 225;
                    int height = 225;

                    Bitmap outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

                    ImageSegmenter.Model model= Segmenter.getCurrentModel();
                    Utils.Companion.segmentResultToBitmap(outputArray, model.getColors(), outputBitmap);
                    mMiniView= findViewById(R.id.MiniView);
                    mMiniView.setImageBitmap(Utils.Companion.resizeBitmap(outputBitmap, width, height));

                    // This would take the output of the segmentation, then convert it to audio
                    voiceOutput(segmentToVoice(outputBitmap));

                    mHandler.postDelayed(this, 1000);
                }
            }, 1000);

        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            camera.close();
            mCamera = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int i) {
            camera.close();
            mCamera = null;
        }
    };

    private HandlerThread mBackgroundHandlerThread;
    private Handler mBackgroundHandler;

    private void startBackgroundThread() {
        mBackgroundHandlerThread = new HandlerThread("Camera");
        mBackgroundHandlerThread.start();
        mBackgroundHandler = new Handler(mBackgroundHandlerThread.getLooper());
    }

    private void stopBackgroundThread() {
        mBackgroundHandlerThread.quitSafely();
        try {
            mBackgroundHandlerThread.join();
            mBackgroundHandlerThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

    }

    private String mCameraID;
    private static SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 0);
        ORIENTATIONS.append(Surface.ROTATION_90, 90);
        ORIENTATIONS.append(Surface.ROTATION_180, 180);
        ORIENTATIONS.append(Surface.ROTATION_270, 270);
    }

    private Size mPreviewSize;
    private CaptureRequest.Builder mCaptureRequestBuilder;

    private static class CompareSizeByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) (lhs.getWidth() * lhs.getHeight()) -
                    (long) (rhs.getWidth() * rhs.getHeight()));
        }
    }


    private static int sensorToDeviceRotation(CameraCharacteristics cameraCharacteristics, int deviceOrientation) {
        int sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        deviceOrientation = ORIENTATIONS.get(deviceOrientation);
        return (sensorOrientation + deviceOrientation + 360) % 360;
    }

    @Override
    protected void onCreate (Bundle savedInstanceState)  {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mPreview = (TextureView) findViewById(R.id.PreviewView);

        mTTs= new TextToSpeech(getApplicationContext(), new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int i) {
                if(i!= TextToSpeech.ERROR){
                    mTTs.setLanguage(Locale.US);

                }
                else{
                    Toast.makeText(MainActivity.this,"ERROR",Toast.LENGTH_SHORT).show();
                }
            }
        });

    }

    @Override
    protected void onResume() {
        super.onResume();

        startBackgroundThread();

        if (mPreview.isAvailable()) {
            setupCamera(mPreview.getWidth(), mPreview.getHeight());
            connectCamera();
        } else {
            mPreview.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }


    protected void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if(requestCode == REQUEST_CAMERA_PERMISSION_RESULT) {
            if(grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(getApplicationContext(), "Application won't run without Permissions.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        View decorView = getWindow().getDecorView();
        if (hasFocus) {
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
            );
        }

    }

    private void setupCamera(int width, int height) {
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String cameraID : cameraManager.getCameraIdList()) {
                CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraID);
                if (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }
                StreamConfigurationMap map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                int deviceOrientation = getWindowManager().getDefaultDisplay().getRotation();
                int totalRotation = sensorToDeviceRotation(cameraCharacteristics, deviceOrientation);
                boolean swapRotation = totalRotation == 90 || totalRotation == 270;
                int rotatedWidth = width;
                int rotatedHeight = height;
                if (swapRotation) {
                    rotatedWidth = height;
                    rotatedHeight = width;
                }
                mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), rotatedWidth, rotatedHeight);
                mCameraID = cameraID;
                return;

            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }


    @SuppressLint("MissingPermission")
    private void connectCamera() {
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if(ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED) {
                    cameraManager.openCamera(mCameraID, mCameraDeviceStateCallback, mBackgroundHandler);
                } else {
                    if(shouldShowRequestPermissionRationale(android.Manifest.permission.CAMERA)) {
                        Toast.makeText(this,
                                "Video app required access to camera", Toast.LENGTH_SHORT).show();
                    }
                    requestPermissions(new String[] {android.Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO
                    }, REQUEST_CAMERA_PERMISSION_RESULT);
                }

            } else {
                cameraManager.openCamera(mCameraID, mCameraDeviceStateCallback, mBackgroundHandler);
            }        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void startPreview() {
        SurfaceTexture surfaceTexture = mPreview.getSurfaceTexture();
        surfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
        Surface previewSurface = new Surface(surfaceTexture);

        try {
            mCaptureRequestBuilder = mCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mCaptureRequestBuilder.addTarget(previewSurface);

            mCamera.createCaptureSession(Arrays.asList(previewSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            try {
                                session.setRepeatingRequest(mCaptureRequestBuilder.build(), null, mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }
                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                            Toast.makeText(getApplicationContext(), "Unable to setup Preview", Toast.LENGTH_SHORT).show();
                        }
                    }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void closeCamera() {
        if(mCamera != null) {
            mCamera.close();
        }
    }

    private static Size chooseOptimalSize(Size[] choices, int width, int height) {

        List<Size> bigEnough = new ArrayList<Size>();
        for(Size option : choices) {
            if(option.getHeight() == option.getWidth() * height / width &&
                    option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }
        if(bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizeByArea());
        } else {
            return choices[0];
        }
    }

    // ✅ Updated segmentToVoice + voiceOutput to provide detailed guidance for blind navigation app

    // ✅ Enhanced logic: Don't stop if a clear floor path exists

    public int segmentToVoice(Bitmap segmOut) {
        int w = segmOut.getWidth();
        int h = segmOut.getHeight();

        int centerStart = w / 3;
        int centerEnd = 2 * w / 3;

        int leftFloor = 0, centerFloor = 0, rightFloor = 0;
        int leftObstacle = 0, centerObstacle = 0, rightObstacle = 0;

        for (int y = h / 2; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pixel = segmOut.getPixel(x, y);
                int r = Color.red(pixel);
                int g = Color.green(pixel);
                int b = Color.blue(pixel);

                boolean isFloor = (r == 128 && g == 64 && b == 128);
                boolean isObstacle = !(isFloor || (r < 30 && g < 30 && b < 30)); // not floor or door

                if (x < centerStart) {
                    if (isFloor) leftFloor++;
                    if (isObstacle) leftObstacle++;
                } else if (x < centerEnd) {
                    if (isFloor) centerFloor++;
                    if (isObstacle) centerObstacle++;
                } else {
                    if (isFloor) rightFloor++;
                    if (isObstacle) rightObstacle++;
                }
            }
        }

        Log.d("DEBUG", "Floor: L=" + leftFloor + ", C=" + centerFloor + ", R=" + rightFloor);
        Log.d("DEBUG", "Obs:   L=" + leftObstacle + ", C=" + centerObstacle + ", R=" + rightObstacle);

        boolean clearLeft = leftFloor > leftObstacle * 1.5;
        boolean clearCenter = centerFloor > centerObstacle * 1.5;
        boolean clearRight = rightFloor > rightObstacle * 1.5;

        if (clearCenter) return 1; // Go ahead
        if (clearRight) return (rightFloor > centerFloor * 1.3) ? 6 : 4;
        if (clearLeft) return (leftFloor > centerFloor * 1.3) ? 7 : 3;

        return 5; // Stop if all blocked
    }

    private void voiceOutput(int voiceLine) {
        if (mTTs.isSpeaking()) return;

        String lineToSpeak;
        switch (voiceLine) {
            case 1:
                lineToSpeak = "Go ahead.";
                break;
            case 3:
                lineToSpeak = "Go left.";
                break;
            case 4:
                lineToSpeak = "Go right.";
                break;
            case 5:
                lineToSpeak = "Stop. There is an obstacle ahead.";
                break;
            case 6:
                lineToSpeak = "Forty five degrees to the right.";
                break;
            case 7:
                lineToSpeak = "Forty five degrees to the left.";
                break;
            default:
                lineToSpeak = "Hold position.";
        }

        Toast.makeText(MainActivity.this, lineToSpeak, Toast.LENGTH_SHORT).show();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mTTs.speak(lineToSpeak, TextToSpeech.QUEUE_ADD, null, null);
        } else {
            mTTs.speak(lineToSpeak, TextToSpeech.QUEUE_ADD, null);
        }
    }

    private void updateSegmPreview(Bitmap segmOutput) {
        Toast.makeText(MainActivity.this, "Updating Preview",Toast.LENGTH_LONG).show();
        mMiniView= findViewById(R.id.MiniView);
        mMiniView.setImageBitmap(segmOutput);
    }

}