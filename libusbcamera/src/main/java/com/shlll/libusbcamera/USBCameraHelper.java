package com.shlll.libusbcamera;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;
import android.widget.Toast;

import com.serenegiant.usb.DeviceFilter;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.utils.HandlerThreadHandler;
import com.serenegiant.widget.UVCCameraTextureView;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class USBCameraHelper {
    private static final String TAG = "USBCameraHelper";
    private final Object mSync = new Object();
    private USBMonitor mUSBMonitor;
    private UVCCamera mUVCCamera;
    private UVCCameraTextureView mUVCCameraView;
    private Surface mPreviewSurface;
    private Context mContext;

    /** Event Handler */
    private Handler mWorkerHandler;
    private long mWorkerThreadID = -1;

    public USBCameraHelper(final Context context, final UVCCameraTextureView cameraView) {
        if (mWorkerHandler == null) {
            mWorkerHandler = HandlerThreadHandler.createHandler(TAG);
            mWorkerThreadID = mWorkerHandler.getLooper().getThread().getId();
        }

        mContext = context;
        mUVCCameraView = cameraView;
        mUVCCameraView.setAspectRatio(UVCCamera.DEFAULT_PREVIEW_WIDTH / (float)UVCCamera.DEFAULT_PREVIEW_HEIGHT);
        mUSBMonitor = new USBMonitor(context, mOnDeviceConnectListener);
    }

    public synchronized void start() {
        mUSBMonitor.register();
        synchronized (mSync) {
            if (mUVCCamera != null) {
                mUVCCamera.startPreview();
            }
        }
    }

    public synchronized void stop() {
        synchronized (mSync) {
            if (mUVCCamera != null) {
                mUVCCamera.stopPreview();
            }
            if (mUSBMonitor != null) {
                mUSBMonitor.unregister();
            }
        }
    }

    public synchronized void destory() {
        synchronized (mSync) {
            releaseCamera();

            if (mUSBMonitor != null) {
                mUSBMonitor.destroy();
                mUSBMonitor = null;
            }
        }
        mUVCCameraView = null;
    }

    public void saveCapturePicture() {
        if (mUVCCamera == null) {
            showShortMsg(mContext.getResources().getString(R.string.msg_camera_open_fail));
            return;
        }

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmssSSS").format(new Date());
        String imageFileName = timeStamp + ".jpg";
        String storagePath = Environment.getExternalStorageDirectory().getAbsolutePath() + File.separator + "USBCamera";
        File storageDir = new File(storagePath);
        if (!storageDir.exists()) {
            storageDir.mkdir();
        }
        String imageFilePath = storagePath + File.separator + imageFileName;
        Log.d(TAG, imageFilePath);

        Bitmap bitmap = mUVCCameraView.getBitmap();

        try {
            File file = new File(imageFilePath);
            FileOutputStream out = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out);
            out.flush();
            out.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        galleryAddPic(imageFilePath);
        showShortMsg(mContext.getResources().getString(R.string.msg_capturesaved));
    }

    private final USBMonitor.OnDeviceConnectListener mOnDeviceConnectListener = new USBMonitor.OnDeviceConnectListener() {
        @Override
        public void onAttach(final UsbDevice device) {
            showShortMsg(mContext.getResources().getString(R.string.msg_usb_device_attached));
            requestPermission();
        }

        @Override
        public void onConnect(final UsbDevice device, final USBMonitor.UsbControlBlock ctrlBlock, final boolean createNew) {
            releaseCamera();
            queueEvent(new Runnable() {
                @Override
                public void run() {
                    final UVCCamera camera = new UVCCamera();
                    camera.open(ctrlBlock);

                    if (mPreviewSurface != null) {
                        mPreviewSurface.release();
                        mPreviewSurface = null;
                    }
                    try {
                        camera.setPreviewSize(UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT, UVCCamera.FRAME_FORMAT_MJPEG);
                    } catch (final IllegalArgumentException e) {
                        // fallback to YUV mode
                        try {
                            camera.setPreviewSize(UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT, UVCCamera.DEFAULT_PREVIEW_MODE);
                        } catch (final IllegalArgumentException e1) {
                            camera.destroy();
                            return;
                        }
                    }
                    final SurfaceTexture st = mUVCCameraView.getSurfaceTexture();
                    if (st != null) {
                        mPreviewSurface = new Surface(st);
                        camera.setPreviewDisplay(mPreviewSurface);
                        camera.startPreview();
                    }
                    synchronized (mSync) {
                        mUVCCamera = camera;
                    }
                }
            }, 0);
        }

        @Override
        public void onDisconnect(final UsbDevice device, final USBMonitor.UsbControlBlock ctrlBlock) {
            releaseCamera();
        }

        @Override
        public void onDettach(final UsbDevice device) {
            showShortMsg(mContext.getResources().getString(R.string.msg_usb_device_detached));
        }

        @Override
        public void onCancel(final UsbDevice device) {
        }
    };

    private void requestPermission() {
        final List<DeviceFilter> filter = DeviceFilter.getDeviceFilters(mContext, com.shlll.libusbcamera.R.xml.device_filter);
        final List<UsbDevice> deviceList = mUSBMonitor.getDeviceList(filter);

        if (deviceList == null || deviceList.size() == 0) {
            return;
        }

        if (mUSBMonitor != null) {
            mUSBMonitor.requestPermission(deviceList.get(0));
        }
    }

    private synchronized void releaseCamera() {
        synchronized (mSync) {
            if (mUVCCamera != null) {
                try {
                    mUVCCamera.setStatusCallback(null);
                    mUVCCamera.setButtonCallback(null);
                    mUVCCamera.close();
                    mUVCCamera.destroy();
                } catch (final Exception e) {
                    //
                }
                mUVCCamera = null;
            }
            if (mPreviewSurface != null) {
                mPreviewSurface.release();
                mPreviewSurface = null;
            }
        }
    }

    private void galleryAddPic(String photoPath) {
        Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        File f = new File(photoPath);
        Uri contentUri = Uri.fromFile(f);
        mediaScanIntent.setData(contentUri);
        mContext.sendBroadcast(mediaScanIntent);
    }

    /**
     * Run runnable specified on worker thread
     * the same runnable that is unexecuted is cancelled (executed only later)
     * @param task
     * @param delayMillis
     */
    protected final synchronized void queueEvent(final Runnable task, final long delayMillis) {
        if ((task == null) || (mWorkerHandler == null)) return;
        try {
            mWorkerHandler.removeCallbacks(task);
            if (delayMillis > 0) {
                mWorkerHandler.postDelayed(task, delayMillis);
            } else if (mWorkerThreadID == Thread.currentThread().getId()) {
                task.run();
            } else {
                mWorkerHandler.post(task);
            }
        } catch (final Exception e) {
        }
    }

    private void showShortMsg(String msg) {
        Toast.makeText(mContext, msg, Toast.LENGTH_SHORT).show();
    }
}
