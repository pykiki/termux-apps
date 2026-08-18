package com.termux.app.api;

import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.NonNull;


import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public class CameraPhotoAPI {

    private static final String LOG_TAG = "CameraPhotoAPI";

    public static void onReceive(final Context context, Intent intent) {
        final String filePath = intent.getStringExtra("file");
        final String cameraId = Objects.toString(intent.getStringExtra("camera"), "0");

        ResultReturner.returnData(intent, stdout -> {
            if (filePath == null || filePath.isEmpty()) {
                stdout.println("ERROR: " + "File path not passed");
                return;
            }

            // The canonicalisation this used to do was commented out when the API moved,
            // leaving the path null and every capture throwing NullPointerException before
            // the camera was even opened. The caller passes an absolute path already.
            takePicture(stdout, context, new File(filePath), cameraId);
        });
    }

    private static void takePicture(final PrintWriter stdout, final Context context, final File outputFile, String cameraId) {
        try {
            final CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

            Looper.prepare();
            final Looper looper = Looper.myLooper();

            //noinspection MissingPermission
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(final CameraDevice camera) {
                    try {
                        proceedWithOpenedCamera(context, manager, camera, outputFile, looper, stdout);
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "Exception in onOpened()", e);
                        // Logging alone left the caller with rc=0 and no photo.
                        reportError(stdout, "capture failed: " + e);
                        closeCamera(camera, looper);
                    }
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    reportError(stdout, "camera " + cameraId + " disconnected before it captured");
                    closeCamera(camera, looper);
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    reportError(stdout, "camera " + cameraId + " could not be opened, error "
                        + error + " - it may be in use by another app");
                    closeCamera(camera, looper);
                }
            }, null);

            Looper.loop();
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error getting camera", e);
            reportError(stdout, "could not reach the camera: " + e);
        }
    }

    /**
     * Every failure below used to be written to the log and nowhere else, so a capture that
     * never happened returned success and left the caller looking at a missing or empty file.
     */
    private static void reportError(PrintWriter stdout, String message) {
        stdout.println("{\"API_ERROR\":\"" + message.replace("\"", "'") + "\"}");
        stdout.flush();
    }

    // See answer on http://stackoverflow.com/questions/31925769/pictures-with-camera2-api-are-really-dark
    // See https://developer.android.com/reference/android/hardware/camera2/CameraDevice.html#createCaptureSession(java.util.List<android.view.Surface>, android.hardware.camera2.CameraCaptureSession.StateCallback, android.os.Handler)
    // for information about guaranteed support for output sizes and formats.
    static void proceedWithOpenedCamera(final Context context, final CameraManager manager, final CameraDevice camera, final File outputFile, final Looper looper, final PrintWriter stdout) throws CameraAccessException, IllegalArgumentException {
        final List<Surface> outputSurfaces = new ArrayList<>();

        final CameraCharacteristics characteristics = manager.getCameraCharacteristics(camera.getId());

        int autoExposureMode = CameraMetadata.CONTROL_AE_MODE_OFF;
        for (int supportedMode : characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)) {
            if (supportedMode == CameraMetadata.CONTROL_AE_MODE_ON) {
                autoExposureMode = supportedMode;
            }
        }
        final int autoExposureModeFinal = autoExposureMode;

        // Use largest available size:
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Comparator<Size> bySize = (lhs, rhs) -> {
            // Cast to ensure multiplications won't overflow:
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        };
        List<Size> sizes = Arrays.asList(map.getOutputSizes(ImageFormat.JPEG));
        Size largest = Collections.max(sizes, bySize);

        final ImageReader mImageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(), ImageFormat.JPEG, 2);
        mImageReader.setOnImageAvailableListener(reader -> new Thread(() -> {
            try (final Image mImage = reader.acquireNextImage()) {
                ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                try (FileOutputStream output = new FileOutputStream(outputFile)) {
                    output.write(bytes);
                } catch (Exception e) {
                    stdout.println("Error writing image: " + e.getMessage());
                    Log.e(LOG_TAG, "Error writing image", e);
                    reportError(stdout, "the photo could not be written: " + e);
                }
            } finally {
                mImageReader.close();
                releaseSurfaces(outputSurfaces);
                closeCamera(camera, looper);
            }
        }).start(), null);
        final Surface imageReaderSurface = mImageReader.getSurface();
        outputSurfaces.add(imageReaderSurface);

        // create a dummy PreviewSurface
        SurfaceTexture previewTexture = new SurfaceTexture(1);
        Surface dummySurface = new Surface(previewTexture);
        outputSurfaces.add(dummySurface);

        camera.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(final CameraCaptureSession session) {
                try {
                    // create preview Request
                    CaptureRequest.Builder previewReq = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                    previewReq.addTarget(dummySurface);
                    previewReq.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                    previewReq.set(CaptureRequest.CONTROL_AE_MODE, autoExposureModeFinal);

                    // continous preview-capture for 1/2 second
                    session.setRepeatingRequest(previewReq.build(), null, null);
                    Log.i(LOG_TAG, "preview started");
                    Thread.sleep(500);
                    session.stopRepeating();
                    Log.i(LOG_TAG, "preview stoppend");

                    final CaptureRequest.Builder jpegRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                    // Render to our image reader:
                    jpegRequest.addTarget(imageReaderSurface);
                    // Configure auto-focus (AF) and auto-exposure (AE) modes:
                    jpegRequest.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                    jpegRequest.set(CaptureRequest.CONTROL_AE_MODE, autoExposureModeFinal);
                    jpegRequest.set(CaptureRequest.JPEG_ORIENTATION, correctOrientation(context, characteristics));

                    saveImage(camera, session, jpegRequest.build());
                } catch (Exception e) {
                    Log.e(LOG_TAG, "onConfigured() error in preview", e);
                    mImageReader.close();
                    releaseSurfaces(outputSurfaces);
                    closeCamera(camera, looper);
                }
            }

            @Override
            public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                Log.e(LOG_TAG, "onConfigureFailed() error in preview");
                mImageReader.close();
                releaseSurfaces(outputSurfaces);
                closeCamera(camera, looper);
            }
        }, null);
    }

    static void saveImage(final CameraDevice camera, CameraCaptureSession session, CaptureRequest request) throws CameraAccessException {
        session.capture(request, new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(@NonNull CameraCaptureSession completedSession, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                Log.i(LOG_TAG, "onCaptureCompleted()");
            }
        }, null);
    }

    /**
     * Determine the correct JPEG orientation, taking into account device and sensor orientations.
     * See https://developer.android.com/reference/android/hardware/Camera.html#setDisplayOrientation(int)
     */
    static int correctOrientation(final Context context, final CameraCharacteristics characteristics) {
        final Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
        final boolean isFrontFacing = lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_FRONT;
        Log.i(LOG_TAG, (isFrontFacing ? "Using" : "Not using") + " a front facing camera.");

        Integer sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        if (sensorOrientation != null) {
            Log.i(LOG_TAG, String.format("Sensor orientation: %s degrees", sensorOrientation));
        } else {
            Log.i(LOG_TAG, "CameraCharacteristics didn't contain SENSOR_ORIENTATION. Assuming 0 degrees.");
            sensorOrientation = 0;
        }

        final int deviceRotation =
                ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
        int deviceOrientation = switch (deviceRotation) {
            case Surface.ROTATION_0 -> 0;
            case Surface.ROTATION_90 -> 90;
            case Surface.ROTATION_180 -> 180;
            case Surface.ROTATION_270 -> 270;
            default -> {
                Log.i(LOG_TAG,
                    String.format("Default display has unknown rotation %d. Assuming 0 degrees.", deviceRotation));
                yield 0;
            }
        };
        Log.i(LOG_TAG, String.format("Device orientation: %d degrees", deviceOrientation));

        int jpegOrientation;
        if (isFrontFacing) {
            jpegOrientation = sensorOrientation + deviceOrientation;
        } else {
            jpegOrientation = sensorOrientation - deviceOrientation;
        }
        // Add an extra 360 because (-90 % 360) == -90 and Android won't accept a negative rotation.
        jpegOrientation = (jpegOrientation + 360) % 360;
        Log.i(LOG_TAG, String.format("Returning JPEG orientation of %d degrees", jpegOrientation));
        return jpegOrientation;
    }

    static void releaseSurfaces(List<Surface> outputSurfaces) {
        for (Surface outputSurface : outputSurfaces) {
            outputSurface.release();
        }
        Log.i(LOG_TAG, "surfaces released");
    }

    static void closeCamera(CameraDevice camera, Looper looper) {
        try {
            camera.close();
        } catch (RuntimeException e) {
            Log.i(LOG_TAG, "Exception closing camera: " + e.getMessage());
        }
        if (looper != null) looper.quit();
    }

}
