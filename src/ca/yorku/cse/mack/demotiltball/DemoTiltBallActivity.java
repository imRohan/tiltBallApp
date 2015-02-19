package ca.yorku.cse.mack.demotiltball;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;
/*
*	Rohan Likhite CSE 4443
*
*/

public class DemoTiltBallActivity extends Activity implements SensorEventListener
{
	final static String MYDEBUG = "MYDEBUG"; // for Log.i messages
	final static int REFRESH_INTERVAL = 20; // milliseconds (screen refreshes @ 50 Hz)
	final float RADIANS_TO_DEGREES = 57.2957795f;
	final int NAVIGATION_BAR_HEIGHT = 48;

	// int constants to setup a mode (see DemoTiltMeter API for discussion)
	final static int ORIENTATION = 0;
	final static int ACCELEROMETER_ONLY = 1;
	final static int ACCELEROMETER_AND_MAGNETIC_FIELD = 2;

	RollingBallPanel rb;

	/*
	 * Below are the alpha values for the low-pass filter. The four values are for the slowest
	 * (NORMAL) to fastest (FASTEST) sampling rates, respectively. These values were determined by
	 * trial and error. There is a trade-off. Generally, lower values produce smooth but sluggish
	 * responses, while higher values produced jerky but fast responses.
	 * 
	 * Furthermore, there is a difference by device, particularly for the FASTEST setting. For
	 * example, the FASTEST sample rate is about 200 Hz on a Nexus 4 but only about 100 Hz on a
	 * Samsung Galaxy Tab 10.1.
	 * 
	 * Fiddle with these, as necessary.
	 */
	final float[] ALPHA_VELOCITY = { 0.99f, 0.80f, 0.40f, 0.15f };
	final float[] ALPHA_POSITION = { 0.50f, 0.30f, 0.15f, 0.10f };
	float alpha;

	private SensorManager sm;
	private Sensor sA, sM, sO;
	int sensorMode;

	float[] orientation = new float[3];
	float[] accValues = new float[3];
	float[] magValues = new float[3];
	float x, y, z, pitch, roll;

	String orderOfControl, pathType, pathWidth;
	int gain;
	int defaultOrientation;
	int deviceOrientation;

	ScreenRefreshTimer refreshScreen;

	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);

		// get parameters selected by user from setup dialog
		Bundle b = getIntent().getExtras();
		orderOfControl = b.getString("orderOfControl");
		gain = b.getInt("gain");
		pathType = b.getString("pathType");
		pathWidth = b.getString("pathWidth");

		// set alpha for low-pass filter (based on sampling rate and order of control)
		if (orderOfControl.equals("Velocity")) // velocity control
			alpha = ALPHA_VELOCITY[2]; // for GAME sampling rate
		else
			// position control
			alpha = ALPHA_POSITION[2]; // for GAME sampling rate

		// determine screen width, screen height, and pixel density
		DisplayMetrics dm = new DisplayMetrics();
		getWindowManager().getDefaultDisplay().getMetrics(dm);
		int w = dm.widthPixels;
		int h = dm.heightPixels;

		defaultOrientation = getDefaultDeviceOrientation();
		if (defaultOrientation == Configuration.ORIENTATION_LANDSCAPE)
			h -= NAVIGATION_BAR_HEIGHT;

		float scalingFactor = dm.density;

		// configure rolling ball panel, as per screen parameters and setup parameters
		rb = (RollingBallPanel)findViewById(R.id.rollingballpanel);
		rb.configure((float)w, (float)h, scalingFactor, pathType, pathWidth);
		rb.setGain(gain);
		rb.setOrderOfControl(orderOfControl);
		rb.setVibrator((Vibrator)getSystemService(Context.VIBRATOR_SERVICE));

		// get sensors
		sm = (SensorManager)getSystemService(SENSOR_SERVICE);
		sO = sm.getDefaultSensor(Sensor.TYPE_ORIENTATION); // supported on many devices
		sA = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER); // supported on most devices
		sM = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD); // null on many devices

		// setup the sensor mode (see API for discussion)
		if (sO != null)
		{
			sensorMode = ORIENTATION;
			sA = null;
			sM = null;
			Log.i(MYDEBUG, "Sensor mode: ORIENTATION");
		} else if (sA != null && sM != null)
		{
			sensorMode = ACCELEROMETER_AND_MAGNETIC_FIELD;
			Log.i(MYDEBUG, "Sensor mode: ACCELEROMETER_AND_MAGNETIC_FIELD");
		} else if (sA != null)
		{
			sensorMode = ACCELEROMETER_ONLY;
			Log.i(MYDEBUG, "Sensor mode: ACCELEROMETER_ONLY");
		} else
		{
			Log.i(MYDEBUG, "Can't run demo.  Requires Orientation sensor or Accelerometer");
			this.finish();
		}

		// NOTE: sensor listeners are registered in onResume

		// setup the screen refresh timer (updates every REFRESH_INTERVAL milliseconds)
		refreshScreen = new ScreenRefreshTimer(REFRESH_INTERVAL, REFRESH_INTERVAL);
		refreshScreen.start();
	}

	/*
	 * Get the default orientation of the device. This is needed to correctly map the
	 * sensor data for pitch and roll (see onSensorChanged).
	 */
	public int getDefaultDeviceOrientation()
	{
		WindowManager windowManager = (WindowManager)getSystemService(WINDOW_SERVICE);
		Configuration config = getResources().getConfiguration();
		int rotation = windowManager.getDefaultDisplay().getRotation();

		if (((rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) && config.orientation == Configuration.ORIENTATION_LANDSCAPE)
				|| ((rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) && config.orientation == Configuration.ORIENTATION_PORTRAIT))
			return Configuration.ORIENTATION_LANDSCAPE;
		else
			return Configuration.ORIENTATION_PORTRAIT;
	}

	@Override
	protected void onResume()
	{
		super.onResume();
		sm.registerListener(this, sO, SensorManager.SENSOR_DELAY_GAME); // good enough!
		sm.registerListener(this, sA, SensorManager.SENSOR_DELAY_GAME);
		sm.registerListener(this, sM, SensorManager.SENSOR_DELAY_GAME);
	}

	@Override
	protected void onPause()
	{
		super.onPause();
		sm.unregisterListener(this);
	}

	@Override
	public void onAccuracyChanged(Sensor sensor, int accuracy)
	{
		// not needed but we need to provide an implementation anyway
	}

	@Override
	public void onSensorChanged(SensorEvent se)
	{
		// ===============================
		// DETERMINE DEVICE PITCH AND ROLL
		// ===============================

		switch (sensorMode)
		{
			case ORIENTATION: // ========================================================

				/*
				 * Use this mode if the device has an orientation sensor.
				 */

				if (se.sensor.getType() != Sensor.TYPE_ORIENTATION)
				{
					Log.i(MYDEBUG, "Return now. Sensor event from " + se.sensor.getName());
					return;
				}

				// This bit of fiddling is necessary so the app will work on different devices.
				switch (defaultOrientation)
				{
					case Configuration.ORIENTATION_PORTRAIT:
					{
						// e.g., Nexus 4
						pitch = se.values[1];
						roll = se.values[2];
						break;
					}
					case Configuration.ORIENTATION_LANDSCAPE:
					{
						// e.g., Samsung Galaxy Tab 10.1
						pitch = se.values[2];
						roll = -se.values[1];
						break;
					}
				}
				break;

			case ACCELEROMETER_AND_MAGNETIC_FIELD: // ===================================

				/*
				 * Use this mode if the device has both an accelerometer and a magnetic field sensor
				 * (but no orientation sensor). See...
				 * 
				 * http://blog.thomnichols.org/2012/06/smoothing-sensor-data-part-2
				 */

				// smooth the sensor values using a low-pass filter
				if (se.sensor.getType() == Sensor.TYPE_ACCELEROMETER)
					accValues = lowPass(se.values.clone(), accValues, alpha);
				if (se.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD)
					magValues = lowPass(se.values.clone(), magValues, alpha);

				if (accValues != null && magValues != null)
				{
					// compute pitch and roll
					float R[] = new float[9];
					float I[] = new float[9];
					boolean success = SensorManager.getRotationMatrix(R, I, accValues, magValues);
					if (success) // see SensorManager API
					{
						float[] orientation = new float[3];
						SensorManager.getOrientation(R, orientation); // see getOrientation API
						pitch = orientation[1] * RADIANS_TO_DEGREES;
						roll = -orientation[2] * RADIANS_TO_DEGREES;
					}
				}
				break;

			case ACCELEROMETER_ONLY: // =================================================

				/*
				 * Use this mode if the device has an accelerometer but no magnetic field sensor and
				 * no orientation sensor (e.g., HTC Desire C, Asus MeMOPad). This algorithm doesn't
				 * work quite as well, unfortunately. See...
				 * 
				 * http://www.hobbytronics.co.uk/accelerometer-info
				 */

				// smooth the sensor values using a low-pass filter
				if (se.sensor.getType() == Sensor.TYPE_ACCELEROMETER)
					accValues = lowPass(se.values.clone(), accValues, alpha);

				x = accValues[0];
				y = accValues[1];
				z = accValues[2];
				pitch = (float)Math.atan(y / Math.sqrt(x * x + z * z)) * RADIANS_TO_DEGREES;
				roll = (float)Math.atan(x / Math.sqrt(y * y + z * z)) * RADIANS_TO_DEGREES;
				break;
		}
	}

	/*
	 * Low pass filter. The algorithm requires tracking only two numbers - the prior number and the
	 * new number. There is a time constant "alpha" which determines the amount of smoothing. Alpha
	 * is like a "weight" or "momentum". It determines the effect of the new value on the current
	 * smoothed value.
	 * 
	 * A lower alpha means more smoothing. NOTE: 0 <= alpha <= 1.
	 * 
	 * See...
	 * 
	 * http://blog.thomnichols.org/2011/08/smoothing-sensor-data-with-a-low-pass-filter
	 */
	protected float[] lowPass(float[] input, float[] output, float alpha)
	{
		for (int i = 0; i < input.length; i++)
			output[i] = output[i] + alpha * (input[i] - output[i]);
		return output;
	}

	/*
	 * Screen updates are done in onFinish which executes every REFRESH_INTERVAL milliseconds
	 */
	public class ScreenRefreshTimer extends CountDownTimer
	{
		ScreenRefreshTimer(long millisInFuture, long countDownInterval)
		{
			super(millisInFuture, countDownInterval);
		}

		@Override
		public void onTick(long millisUntilFinished)
		{
		}

		@Override
		public void onFinish()
		{
			float tiltMagnitude = (float)Math.sqrt(pitch * pitch + roll * roll);
			float tiltAngle = tiltMagnitude == 0f ? 0f : (float)Math.asin(roll / tiltMagnitude) * RADIANS_TO_DEGREES;

			if (pitch > 0 && roll > 0)
				tiltAngle = 360f - tiltAngle;
			else if (pitch > 0 && roll < 0)
				tiltAngle = -tiltAngle;
			else if (pitch < 0 && roll > 0)
				tiltAngle = tiltAngle + 180f;
			else if (pitch < 0 && roll < 0)
				tiltAngle = tiltAngle + 180f;

			rb.updateBallPosition(pitch, roll, tiltAngle, tiltMagnitude); // will invalidate ball panel
			this.start();
		}
	}
}