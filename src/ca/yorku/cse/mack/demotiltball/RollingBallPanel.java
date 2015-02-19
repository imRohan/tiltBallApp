package ca.yorku.cse.mack.demotiltball;

import ca.yorku.cse.mack.demotiltball.R;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Vibrator;
import android.util.AttributeSet;
import android.view.View;

public class RollingBallPanel extends View
{
	final float DEGREES_TO_RADIANS = 0.0174532925f;
	final int DEFAULT_BALL_DIAMETER = 10;
	
	// the ball diameter will be min(screenWidth, screenHeight) / this_value
	final float BALL_DIAMETER_ADJUST_FACTOR = 30; 

	final int DEFAULT_LABEL_TEXT_SIZE = 20; // tweak as necessary
	final int DEFAULT_STATS_TEXT_SIZE = 10;
	final int DEFAULT_GAP = 7; // between lines of text
	final int DEFAULT_OFFSET = 10; // from bottom of display

	final int MODE_NONE = 0;
	final int PATH_TYPE_SQUARE = 1;
	final int PATH_TYPE_CIRCLE = 2;

	final float PATH_WIDTH_NARROW = 2.0f; // ... x ball diameter
	final float PATH_WIDTH_MEDIUM = 4.0f; // ... x ball diameter
	final float PATH_WIDTH_WIDE = 8.0f; // ... x ball diameter

	int pathType;
	float radiusOuter, radiusInner;

	Bitmap ball, temp;
	int ballDiameter;

	float dT; // time since last sensor event (seconds)

	float screenWidth, screenHeight, scalingFactor;
	int labelTextSize, statsTextSize, gap, offset;

	RectF innerRectangle, outerRectangle, innerShadowRectangle, outerShadowRectangle, ballNow;
	float pathWidth;
	boolean touchFlag;
	Vibrator vib;
	int wallHits;

	float xBall, yBall; // top-left of the ball (for painting)
	float xBallCenter, yBallCenter; // center of the ball

	float pitch, roll;
	float tiltAngle, tiltMagnitude;
	String orderOfControl;
	float gain;
	float velocity; // in pixels/second (velocity = tiltMagnitude * tiltVelocityGain
	float dBall; // the amount to move the ball (in pixels): dBall = dT * velocity
	float xCenter, yCenter; // the center of the screen
	long now, lastT, startTime, elapsedTime;
	Paint statsPaint, labelPaint, linePaint, fillPaint, backgroundPaint;

	public RollingBallPanel(Context contextArg)
	{
		super(contextArg);
		initialize();
	}

	public RollingBallPanel(Context contextArg, AttributeSet attrs)
	{
		super(contextArg, attrs);
		initialize();
	}

	public RollingBallPanel(Context contextArg, AttributeSet attrs, int defStyle)
	{
		super(contextArg, attrs, defStyle);
		initialize();
	}

	// things that can be initialized from within this View
	private void initialize()
	{
		linePaint = new Paint();
		linePaint.setColor(Color.RED);
		linePaint.setStyle(Paint.Style.STROKE);
		linePaint.setStrokeWidth(2);
		linePaint.setAntiAlias(true);

		fillPaint = new Paint();
		fillPaint.setColor(0xffccbbbb);
		fillPaint.setStyle(Paint.Style.FILL);

		backgroundPaint = new Paint();
		backgroundPaint.setColor(Color.LTGRAY);
		backgroundPaint.setStyle(Paint.Style.FILL);

		labelPaint = new Paint();
		labelPaint.setColor(Color.BLACK);
		labelPaint.setTextSize(DEFAULT_LABEL_TEXT_SIZE);
		labelPaint.setAntiAlias(true);

		statsPaint = new Paint();
		statsPaint.setAntiAlias(true);
		statsPaint.setTextSize(DEFAULT_STATS_TEXT_SIZE);

		lastT = System.nanoTime();

		ballDiameter = DEFAULT_BALL_DIAMETER;
		temp = BitmapFactory.decodeResource(getResources(), R.drawable.ball);
		ball = Bitmap.createScaledBitmap(temp, ballDiameter, ballDiameter, true);

		this.setBackgroundColor(Color.LTGRAY);

		pathWidth = PATH_WIDTH_MEDIUM * ballDiameter; // default
		touchFlag = false;

		outerRectangle = new RectF();
		innerRectangle = new RectF();
		innerShadowRectangle = new RectF();
		outerShadowRectangle = new RectF();
		ballNow = new RectF();
		wallHits = 0;
	}

	public void setGain(float gainArg)
	{
		gain = gainArg;
	}

	public void setOrderOfControl(String orderOfControlArg)
	{
		orderOfControl = orderOfControlArg;
	}

	/*
	 * Do the heavy lifting here! Update the ball position based on the tilt angle, tilt
	 * magnitude, order of control, etc.
	 */
	public void updateBallPosition(float pitchArg, float rollArg, float tiltAngleArg, float tiltMagnitudeArg)
	{
		pitch = pitchArg; // for information only (see onDraw)
		roll = rollArg; // for information only (see onDraw)
		tiltAngle = tiltAngleArg;
		tiltMagnitude = tiltMagnitudeArg;

		// get current time and delta since last onDraw
		now = System.nanoTime();
		dT = (now - lastT) / 1000000000f; // seconds
		lastT = now;

		// don't allow tiltMagnitude to exceed 45 degrees
		final float MAX_MAGNITUDE = 45f;
		tiltMagnitude = tiltMagnitude > MAX_MAGNITUDE ? MAX_MAGNITUDE : tiltMagnitude;

		// This is the only code that distinguishes velocity-control from position-control
		if (orderOfControl.equals("Velocity")) // velocity control
		{
			// compute how far the ball should move
			velocity = tiltMagnitude * gain;
			dBall = dT * velocity; // make the ball move this amount (pixels)

			// compute the ball's new coordinates
			float dx = (float)Math.sin(tiltAngle * DEGREES_TO_RADIANS) * dBall;
			float dy = -(float)Math.cos(tiltAngle * DEGREES_TO_RADIANS) * dBall;
			xBall += dx;
			yBall += dy;
		} else
		// position control
		{
			// compute how far the ball should move
			dBall = tiltMagnitude * gain;

			// compute the ball's new coordinates
			float dx = (float)Math.sin(tiltAngle * DEGREES_TO_RADIANS) * dBall;
			float dy = -(float)Math.cos(tiltAngle * DEGREES_TO_RADIANS) * dBall;
			xBall = xCenter + dx;
			yBall = yCenter + dy;
		}

		// keep the ball visible (also, restore if NaN)
		if (Float.isNaN(xBall) || xBall < 0)
			xBall = 0;
		else if (xBall > screenWidth - ballDiameter)
			xBall = screenWidth - ballDiameter;
		if (Float.isNaN(yBall) || yBall < 0)
			yBall = 0;
		else if (yBall > screenHeight - ballDiameter)
			yBall = screenHeight - ballDiameter;

		xBallCenter = xBall + ballDiameter / 2f;
		yBallCenter = yBall + ballDiameter / 2f;

		// if ball touches wall, vibrate and increment wallHits count
		if (ballTouchingLine() && !touchFlag)
		{
			touchFlag = true;
			vib.vibrate(10); // 10 ms vibrotactile pulse
			++wallHits;
		} else if (!ballTouchingLine() && touchFlag)
			touchFlag = false;

		invalidate(); // force onDraw to redraw the screen with the ball in its new position
	}

	protected void onDraw(Canvas canvas)
	{
		// draw the paths
		if (pathType == PATH_TYPE_SQUARE)
		{
			// draw fills
			canvas.drawRect(outerRectangle, fillPaint);
			canvas.drawRect(innerRectangle, backgroundPaint);

			// draw lines
			canvas.drawRect(outerRectangle, linePaint);
			canvas.drawRect(innerRectangle, linePaint);
		} else if (pathType == PATH_TYPE_CIRCLE)
		{
			// draw fills
			canvas.drawOval(outerRectangle, fillPaint);
			canvas.drawOval(innerRectangle, backgroundPaint);

			// draw lines
			canvas.drawOval(outerRectangle, linePaint);
			canvas.drawOval(innerRectangle, linePaint);
		}

		// draw label
		canvas.drawText("Demo Tilt Ball", 6, labelTextSize, labelPaint);

		// draw stats (pitch, roll, tilt angle, tilt magnitude)
		if (pathType == PATH_TYPE_SQUARE || pathType == PATH_TYPE_CIRCLE)
		{
			canvas.drawText("Wall hits = " + wallHits, 6f, screenHeight - offset - 5f * (statsTextSize + gap),
					statsPaint);

			canvas.drawText("-----------------", 6f, screenHeight - offset - 4f * (statsTextSize + gap), statsPaint);
		}

		canvas.drawText("Tablet pitch (degrees) = " + trim(pitch, 2), 6f, screenHeight - offset - 3f
				* (statsTextSize + gap), statsPaint);

		canvas.drawText("Tablet roll (degrees) = " + trim(roll, 2), 6f, screenHeight - offset - 2f
				* (statsTextSize + gap), statsPaint);

		canvas.drawText("Ball x = " + trim(xBallCenter, 2), 6f, screenHeight - offset - 1f * (statsTextSize + gap),
				statsPaint);

		canvas.drawText("Ball y = " + trim(yBallCenter, 2), 6f, screenHeight - offset - 0f * (statsTextSize + gap),
				statsPaint);

		// draw the ball in its new location
		canvas.drawBitmap(ball, xBall, yBall, null);

	} // end onDraw

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec)
	{
		setMeasuredDimension(measureWidth(widthMeasureSpec), measureHeight(heightMeasureSpec));
	}

	private int measureWidth(int widthMeasureSpec)
	{
		return (int)screenWidth;
	}

	private int measureHeight(int heightMeasureSpec)
	{
		return (int)screenHeight;
	}

	/*
	 * Configure the rolling ball panel according to screen size, path parameters, etc.
	 */
	public void configure(float w, float h, float scalingFactorArg, String pathMode, String pathWidthArg)
	{
		screenWidth = w;
		screenHeight = h;
		scalingFactor = scalingFactorArg;

		if (pathMode.equals("Square"))
			pathType = PATH_TYPE_SQUARE;
		else if (pathMode.equals("Circle"))
			pathType = PATH_TYPE_CIRCLE;
		else
			pathType = MODE_NONE;

		if (pathWidthArg.equals("Narrow"))
			pathWidth = PATH_WIDTH_NARROW;
		else if (pathWidthArg.equals("Wide"))
			pathWidth = PATH_WIDTH_WIDE;
		else
			pathWidth = PATH_WIDTH_MEDIUM;

		xCenter = w / 2f;
		yCenter = h / 2f;
		xBall = xCenter;
		yBall = yCenter;
		xBallCenter = xBall + ballDiameter / 2f;
		yBallCenter = yBall + ballDiameter / 2f;

		ballDiameter = screenWidth < screenHeight ? (int)(screenWidth / BALL_DIAMETER_ADJUST_FACTOR)
				: (int)(screenHeight / BALL_DIAMETER_ADJUST_FACTOR);

		ball = Bitmap.createScaledBitmap(temp, ballDiameter, ballDiameter, true);

		radiusOuter = screenWidth < screenHeight ? 0.40f * screenWidth : 0.40f * screenHeight;
		outerRectangle.left = xCenter - radiusOuter;
		outerRectangle.top = yCenter - radiusOuter;
		outerRectangle.right = xCenter + radiusOuter;
		outerRectangle.bottom = yCenter + radiusOuter;

		// NOTE: path width is 4 x ball diameter
		radiusInner = radiusOuter - pathWidth * ballDiameter;

		innerRectangle.left = xCenter - radiusInner;
		innerRectangle.top = yCenter - radiusInner;
		innerRectangle.right = xCenter + radiusInner;
		innerRectangle.bottom = yCenter + radiusInner;

		// NOTE: line thickness (aka stroke width) is 2
		outerShadowRectangle.left = outerRectangle.left + ballDiameter - 2f;
		outerShadowRectangle.top = outerRectangle.top + ballDiameter - 2f;
		outerShadowRectangle.right = outerRectangle.right - ballDiameter + 2f;
		outerShadowRectangle.bottom = outerRectangle.bottom - ballDiameter + 2f;

		innerShadowRectangle.left = innerRectangle.left + ballDiameter - 2f;
		innerShadowRectangle.top = innerRectangle.top + ballDiameter - 2f;
		innerShadowRectangle.right = innerRectangle.right - ballDiameter + 2f;
		innerShadowRectangle.bottom = innerRectangle.bottom - ballDiameter + 2f;

		labelTextSize = (int)(DEFAULT_LABEL_TEXT_SIZE * scalingFactor + 0.5f);
		labelPaint.setTextSize(labelTextSize);

		statsTextSize = (int)(DEFAULT_STATS_TEXT_SIZE * scalingFactor + 0.5f);
		statsPaint.setTextSize(statsTextSize);

		gap = (int)(DEFAULT_GAP * scalingFactor + 0.5f);
		offset = (int)(DEFAULT_OFFSET * scalingFactor + 0.5f);
	}

	public void setVibrator(Vibrator v)
	{
		vib = v;
	}

	// trim and round a float to the specified number of decimal places
	private float trim(float f, int decimalPlaces)
	{
		return (int)(f * 10 * decimalPlaces + 0.5f) / (float)(10 * decimalPlaces);
	}

	// returns true if the ball is touching the line of the inner or outer square/circle
	public boolean ballTouchingLine()
	{
		if (pathType == PATH_TYPE_SQUARE)
		{
			ballNow.left = xBall;
			ballNow.top = yBall;
			ballNow.right = xBall + ballDiameter;
			ballNow.bottom = yBall + ballDiameter;

			if (RectF.intersects(ballNow, outerRectangle) && !RectF.intersects(ballNow, outerShadowRectangle))
				return true; // touching outside square

			if (RectF.intersects(ballNow, innerRectangle) && !RectF.intersects(ballNow, innerShadowRectangle))
				return true; // touching inside square
		}

		else if (pathType == PATH_TYPE_CIRCLE)
		{
			final float ballDistance = (float)Math.sqrt((xBallCenter - xCenter) * (xBallCenter - xCenter)
					+ (yBallCenter - yCenter) * (yBallCenter - yCenter));

			if (Math.abs(ballDistance - radiusOuter) < (ballDiameter / 2f))
				return true; // touching outer circle

			if (Math.abs(ballDistance - radiusInner) < (ballDiameter / 2f))
				return true; // touching inner circle
		}
		return false;
	}
}
