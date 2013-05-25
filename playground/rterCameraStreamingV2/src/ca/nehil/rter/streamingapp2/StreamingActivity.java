/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ca.nehil.rter.streamingapp2;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.SocketException;
import java.net.URL;
import java.util.Date;
import java.util.Random;

import ca.nehil.rter.streamingapp2.overlay.*;


import android.annotation.SuppressLint;
import android.app.Activity;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;

import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.provider.Settings.Secure;

import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaRecorder;
import android.provider.Settings;
import android.net.wifi.WifiConfiguration;


// ----------------------------------------------------------------------

public class StreamingActivity extends Activity implements 
		LocationListener {
	

	private SharedPreferences cookies;
	private SharedPreferences.Editor prefEditor;
	private String setRterResource;
	private String setRterSignature;
	private String setRterValidUntil;
	public static String SOCKET_ADDRESS = "ca.nehil.rter.streamingapp2.socketserver";
	private Handler handler = new Handler();
	static ParcelFileDescriptor pfd=null;
	static FileDescriptor  fd=null;

	
	private Preview mPreview;
	public MediaRecorder mrec = new MediaRecorder();
	private FrameLayout mFrame; // need this to merge camera preview and openGL
								// view
	private CameraGLSurfaceView mGLView;
	private OverlayController overlay;
	SensorManager mSensorManager;
	Sensor mAcc, mMag;
	Camera mCamera;
	int numberOfCameras;
	int cameraCurrentlyLocked;

	int defaultCameraId;
	static boolean isFPS = false;

	private String AndroidId;
	private String selected_uid; // passed from other activity right now

	
	
	private LocationManager locationManager;
	private String provider;
	
	FrameInfo frameInfo;

	// to prevent sleeping
	PowerManager pm;
	PowerManager.WakeLock wl;

	private static final String TAG = "Streaming Activity";
	//protected static final String MEDIA_TYPE_IMAGE = null;
	
	public class NotificationRunnable implements Runnable {
        private String message = null;
        
        public void run() {
            if (message != null && message.length() > 0) {
                showNotification(message);
            }
        }
        
        /**
        * @param message the message to set
        */
        public void setMessage(String message) {
            this.message = message;
        }
    }
    
    // post this to the Handler when the background thread notifies
    private final NotificationRunnable notificationRunnable = new NotificationRunnable();
    
    public void showNotification(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
    
    class SocketListener extends Thread {
        private Handler handler = null;
        private NotificationRunnable runnable = null;
        
        public SocketListener(Handler handler, NotificationRunnable runnable) {
            this.handler = handler;
            this.runnable = runnable;
            this.handler.post(this.runnable);
        }
        
        /**
        * Show UI notification.
        * @param message
        */
        private void showMessage(String message) {
            this.runnable.setMessage(message);
            this.handler.post(this.runnable);
        }
        
        @Override
        public void run() {
            showMessage("DEMO: SocketListener started!");
            try {
                LocalServerSocket server = new LocalServerSocket(SOCKET_ADDRESS);
                while (true) {
                    LocalSocket receiver = server.accept();
                    if (receiver != null) {
                    	DataInputStream in = new DataInputStream (receiver.getInputStream());
//                        FileOutputStream videoFile = new FileOutputStream(getOutputMediaFile());
                        // simply for java.util.ArrayList
                    	URL url = new URL(setRterResource+"/ts");
        				HttpURLConnection httpcon = (HttpURLConnection) url.openConnection();
        				httpcon.setDoOutput(true);
        				String auth = "rtER rter_resource="+setRterResource
        						+", rter_valid_until="+setRterValidUntil
        						+", rter_signature="+setRterSignature;
        				httpcon.setRequestProperty("Authorization", auth);
//        				httpcon.setRequestProperty("Accept", "application/json");
        				
        				httpcon.setRequestMethod("POST");
//        				httpcon.setConnectTimeout(TIMEOUT_MILLISEC);
//        				httpcon.setReadTimeout(TIMEOUT_MILLISEC);
        				httpcon.connect();
        				
        				
                        int len;
                        int capacity = 8192;
                        byte buffer[] = new byte[capacity];
                        while((len = in.read(buffer)) != -1) {
                        	OutputStream os = httpcon.getOutputStream();
            				os.write(buffer);

            				os.close();
                            Log.v("videodata",""+buffer.toString());
                        }
                        showMessage("Bufffer recieved!");
                        receiver.close();
                       
                    }
                }
            } catch (IOException e) {
                Log.e(getClass().getName(), e.getMessage());
            }
        }
    }
    public static LocalSocket sender;
    public static void writeSocket() throws IOException {
        sender = new LocalSocket();
        sender.connect(new LocalSocketAddress(SOCKET_ADDRESS));
        fd = sender.getFileDescriptor();
        //handle the closing 
//        sender.getOutputStream().write(message.getBytes());
//        sender.getOutputStream().close();
    }
	
	
	@Override
    protected void onDestroy() {
        stopRecording();
        super.onDestroy();
    }
	

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		menu.add(0, 0, 0, "Start");
		return true;
	}
	
	@Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
        if(item.getTitle().equals("Start"))
        {
            
        	
        	try {
                long starttime =  System.currentTimeMillis();
            	startRecording();
                item.setTitle("Stop");

            } 
            catch(SocketException e){
            	String message = e.getMessage();
                Log.e(null, "Problem " + message);
            	e.printStackTrace();
            	mrec.release();
            }catch (IOException e) {
                Log.e(getClass().getName(), e.getMessage());
                e.printStackTrace();
                mrec.release();
            }

        }
        else if(item.getTitle().equals("Stop"))
        {
            mrec.stop();
            mrec.release();
            mrec = null;
            item.setTitle("Start");
        }

        return super.onOptionsItemSelected(item);
    }
	
	protected void startRecording() throws IOException
    {
        if(mCamera==null)
         mCamera = Camera.open();
        
        
        sender = new LocalSocket();
        sender.connect(new LocalSocketAddress(SOCKET_ADDRESS));
        fd = sender.getFileDescriptor();
        
    	
        String filename;
        String root = (Environment.getExternalStorageDirectory()).toString();
 		File rootDir = new File(Environment.getExternalStorageDirectory()
 				+ File.separator + "rter" + File.separator);
 		rootDir.mkdirs();
        Date date=new Date();
        filename="/rec"+date.toString().replace(" ", "_").replace(":", "_")+".ts";
        //create empty file it must use
        File file=new File(rootDir,filename);
        
        
        
        mrec = new MediaRecorder(); 

        mCamera.lock();
        mCamera.unlock();

        // Please maintain sequence of following code. 

        // If you change sequence it will not work
        mrec.setCamera(mCamera);    
        mrec.setVideoSource(MediaRecorder.VideoSource.CAMERA);
        //mrec.setAudioSource(MediaRecorder.AudioSource.MIC);     
//        mrec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mrec.setOutputFormat(8);
        mrec.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        //mrec.setAudioEncoder(MediaRecorder.AudioEncoder.DEFAULT);
//        mrec.setOutputFile(filename);
        mrec.setOutputFile(fd);
        mrec.setVideoEncodingBitRate(600000);
        //mrec.setAudioEncodingBitRate(44100);
        mrec.setVideoFrameRate(15);
        //mrec.setMaxDuration(2000);
        mrec.setPreviewDisplay(mPreview.mHolder.getSurface());
        mrec.prepare();
        mrec.start();
        
       

        
    }

    protected void stopRecording() {

        if(mrec!=null)
        {
        	
        	try {
				sender.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
        	mrec.stop();
            mrec.release();
            mCamera.release();
            mCamera.lock();
        }
    }

    private void releaseMediaRecorder() {

        if (mrec != null) {
            mrec.reset(); // clear recorder configuration
            mrec.release(); // release the recorder object
        }
    }

    private void releaseCamera() {
        if (mCamera != null) {
            mCamera.release(); // release the camera for other applications
            mCamera = null;
        }

    }
    
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Log.e(TAG, "onCreate");
		// Hide the window title.
//		requestWindowFeature(Window.FEATURE_NO_TITLE);
//		getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
		AndroidId = Settings.Secure.getString(getContentResolver(),
		         Settings.Secure.ANDROID_ID);
		frameInfo = new FrameInfo();

		// openGL overlay
		overlay = new OverlayController(this);

		// orientation
		mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
		mAcc = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
		mMag = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);

		// the frame layout will contain the camera preview and the gl view
		mFrame = new FrameLayout(this);

		// Create a RelativeLayout container that will hold a SurfaceView,
		// and set it as the content of our activity.
		mPreview = new Preview(this);

		// openGLview
		mGLView = overlay.getGLView();

		Log.e(TAG, "Fileoutput in phone id " + AndroidId);

		// add the two views to the frame
		mFrame.addView(mPreview);
		mFrame.addView(mGLView);

//		mPreview.setOnClickListener(this);

		setContentView(mFrame);

		// Find the total number of cameras available
		numberOfCameras = Camera.getNumberOfCameras();

		// Find the ID of the default camera
		CameraInfo cameraInfo = new CameraInfo();
		for (int i = 0; i < numberOfCameras; i++) {
			Camera.getCameraInfo(i, cameraInfo);
			Log.d(TAG, "Camera Id is " + i);
			if (cameraInfo.facing == CameraInfo.CAMERA_FACING_BACK) {
				Log.d(TAG, "Back facing camera chosen");
				defaultCameraId = i;
				Log.d(TAG, "defaultcamera ID :"+defaultCameraId );
				break;
			}
//			else if (cameraInfo.facing == CameraInfo.CAMERA_FACING_FRONT) {
//				Log.d(TAG, "Front facing camera chosen");
//				defaultCameraId = i;
//				Log.d(TAG, "defaultcamera ID :"+defaultCameraId );
//			}
		}
		cookies = getSharedPreferences("RterUserCreds", MODE_PRIVATE);
		prefEditor = cookies.edit();
		
		setRterResource = cookies.getString("rter_resource", "not-set");
		setRterSignature = cookies.getString("rter_signature", "not-set");
		setRterValidUntil = cookies.getString("rter_valid_until", "not-set");
		
		Log.d(TAG, "Prefs ==> rter_resource:"+setRterResource+" :: rter_signature:" + setRterSignature );
		
		// Get the location manager
		locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
		// Define the criteria how to select the location provider -> use
		// default
		
		if ( !locationManager.isProviderEnabled( LocationManager.GPS_PROVIDER ) ) {
			Log.e(TAG, "GPS not available");
	    }	
		Criteria criteria = new Criteria();
		provider = locationManager.getBestProvider(criteria, false);
		Log.e(TAG, "Requesting location");
		locationManager.requestLocationUpdates(provider, 0, 1, this);
		// register the overlay control for location updates as well, so we get the geomagnetic field
		locationManager.requestLocationUpdates(provider, 0, 1000, overlay);
		if (provider != null) {
			Location location = locationManager.getLastKnownLocation(provider);
			// Initialize the location fields
			if (location != null) {
				System.out.println("Provider " + provider
						+ " has been selected. and location "+ location);
				onLocationChanged(location);
			} else {
				Log.d(TAG, "Location not available");
			}
		}

		// power manager
		pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
		wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, TAG);

		// test, set desired orienation to north
		overlay.letFreeRoam(false);
		overlay.setDesiredOrientation(0.0f);
		CharSequence text = "Tap to start..";
		int duration = Toast.LENGTH_SHORT;

		Toast toast = Toast.makeText(this, text, duration);
		toast.setGravity(Gravity.TOP|Gravity.RIGHT, 0, 0);
		toast.show();
		/*surfaceHolder = surfaceView.getHolder();
	    surfaceHolder.addCallback(this);
	    surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);*/
	    // Run new thread to handle socket communications
	    Thread sendVideo = new SocketListener(this.handler, this.notificationRunnable);
	    sendVideo.start();
	}
	
	@Override
	public void onStop() {
		
	}
	
	

	@Override
	protected void onResume() {
		super.onResume();
		locationManager.requestLocationUpdates(provider, 0, 1, this);
		// register the overlay control for location updates as well, so we get the geomagnetic field
		locationManager.requestLocationUpdates(provider, 0, 1000, overlay);
		// Open the default i.e. the first rear facing camera.
		mCamera = Camera.open();
		cameraCurrentlyLocked = defaultCameraId;
		mPreview.setCamera(mCamera);
		
		// sensors
		mSensorManager.registerListener(overlay, mAcc,
				SensorManager.SENSOR_DELAY_NORMAL);
		mSensorManager.registerListener(overlay, mMag,
				SensorManager.SENSOR_DELAY_NORMAL);

		// acquire wake lock to make sure camera preview remains on and bright
		wl.acquire();
	}

	@Override
	protected void onPause() {
		super.onPause();
		Log.e(TAG, "onPause");
		locationManager.removeUpdates(this);
		locationManager.removeUpdates(overlay);
		
		// stop sensor updates
		mSensorManager.unregisterListener(overlay);
		

		// Because the Camera object is a shared resource, it's very
		// important to release it when the activity is paused.
		if (mCamera != null) {
			mPreview.setCamera(null);
			mCamera.release();
			mCamera = null;
			mPreview.inPreview = false;
		}

		mSensorManager.unregisterListener(overlay);

		// release wake lock to allow phone to sleep
		wl.release();
	}



	public static byte[] convertStringToByteArray(String s) {

		byte[] theByteArray = s.getBytes();

		Log.e(TAG, "length of byte array" + theByteArray.length);
		return theByteArray;

	}

	protected void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
	}

	@Override
	public void onLocationChanged(Location location) {
		// TODO Auto-generated method stub
		Log.d(TAG, "Location Changed");
		String lati = "" + (location.getLatitude());
		String longi = "" + (location.getLongitude());
		frameInfo.lat = convertStringToByteArray(lati);
		frameInfo.lon = convertStringToByteArray(longi);
	}

	@Override
	public void onProviderDisabled(String arg0) {
		// TODO Auto-generated method stub

	}

	@Override
	public void onProviderEnabled(String arg0) {
		// TODO Auto-generated method stub

	}

	@Override
	public void onStatusChanged(String arg0, int arg1, Bundle arg2) {
		// TODO Auto-generated method stub
	}
}

class FrameInfo {
	public byte[] uid;
	public byte[] lat;
	public byte[] lon;
	public byte[] orientation;
}
