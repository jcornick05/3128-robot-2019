package org.team3128.athos.subsystems;

import org.team3128.common.hardware.motor.LazyCANSparkMax;
import org.team3128.common.generics.Threaded;
import org.team3128.common.control.RateLimiter;
import org.team3128.common.control.AsynchronousPid;
import org.team3128.common.control.motion.Path;
import org.team3128.common.control.motion.PurePursuitController;
import org.team3128.common.utility.math.Rotation2D;
import org.team3128.common.utility.NarwhalUtility;

import com.ctre.phoenix.motorcontrol.ControlMode;
import com.ctre.phoenix.motorcontrol.FeedbackDevice;
import com.ctre.phoenix.motorcontrol.NeutralMode;

import com.revrobotics.*;
import com.revrobotics.CANSparkMax.IdleMode;
import com.revrobotics.CANSparkMaxLowLevel.MotorType;

import edu.wpi.first.wpilibj.ADXRS450_Gyro;
import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.SPI;
import edu.wpi.first.wpilibj.Solenoid;
import edu.wpi.first.wpilibj.Timer;
import org.team3128.common.hardware.motor.LazyCANSparkMax;
import org.team3128.common.utility.RobotMath;

import org.team3128.common.utility.Log;

public class NEODrive extends Threaded {

	public enum DriveState {
		TELEOP, PUREPURSUIT, TURN, DONE
	}

	public static class DriveSignal {
		/*
		 * Inches per second for speed
		 */
		public double leftVelocity;
		public double rightVelocity;
		public double leftAcc;
		public double rightAcc;

		public DriveSignal(double left, double right) {
			this(left, 0, right, 0);
		}

		public DriveSignal(double left, double leftAcc, double right, double rightAcc) {
			leftVelocity = left;
			this.leftAcc = leftAcc;
			rightVelocity = right;
			this.rightAcc = rightAcc;
		}
	}

	public static class AutoDriveSignal {
		public DriveSignal command;
		public boolean isDone;

		public AutoDriveSignal(DriveSignal command, boolean isDone) {
			this.command = command;
			this.isDone = isDone;
		}
	}

	private static final NEODrive instance = new NEODrive();

	public static NEODrive getInstance() {
		return instance;
	}

	private ADXRS450_Gyro gyroSensor;
	// private LazyTalonSRX leftTalon, rightTalon, leftSlaveTalon, leftSlave2Talon,
	// rightSlaveTalon, rightSlave2Talon;
	private PurePursuitController autonomousDriver;
	private AsynchronousPid turnPID;
	private DriveState driveState;
	private RateLimiter moveProfiler, turnProfiler;
	private Rotation2D wantedHeading;
	private volatile double driveMultiplier;

	double prevPositionL = 0;
	double prevPositionR = 0;

	public LazyCANSparkMax leftSpark, rightSpark, leftSparkSlave, rightSparkSlave, leftSparkSlave2, rightSparkSlave2;
	private CANPIDController leftSparkPID, rightSparkPID;
	private CANEncoder leftSparkEncoder, rightSparkEncoder;

	private NEODrive() {

		gyroSensor = new ADXRS450_Gyro(SPI.Port.kOnboardCS0);

		leftSpark = new LazyCANSparkMax(Constants.LEFT_DRIVE_FRONT_ID, MotorType.kBrushless);
		leftSparkSlave = new LazyCANSparkMax(Constants.LEFT_DRIVE_MIDDLE_ID, MotorType.kBrushless);
		leftSparkSlave2 = new LazyCANSparkMax(Constants.LEFT_DRIVE_BACK_ID, MotorType.kBrushless);

		rightSpark = new LazyCANSparkMax(Constants.RIGHT_DRIVE_FRONT_ID, MotorType.kBrushless);
		rightSparkSlave = new LazyCANSparkMax(Constants.RIGHT_DRIVE_MIDDLE_ID, MotorType.kBrushless);
		rightSparkSlave2 = new LazyCANSparkMax(Constants.RIGHT_DRIVE_BACK_ID, MotorType.kBrushless);

		leftSpark.setInverted(true);
		rightSpark.setInverted(false);
		leftSparkSlave.setInverted(true);
		rightSparkSlave.setInverted(false);

		leftSparkPID = leftSpark.getPIDController();
		rightSparkPID = rightSpark.getPIDController();
		leftSparkEncoder = leftSpark.getEncoder();
		rightSparkEncoder = rightSpark.getEncoder();

		configMotors();

		driveState = DriveState.TELEOP;

		turnPID = new AsynchronousPid(1.0, 0, 1.2, 0); // P=1.0 OR 0.8
		turnPID.setOutputRange(Constants.DRIVE_HIGH_SPEED, -Constants.DRIVE_HIGH_SPEED);
		turnPID.setSetpoint(0);

		moveProfiler = new RateLimiter(Constants.DRIVE_ACCEL_LIMIT);
		turnProfiler = new RateLimiter(100);

		// configHigh();
		configAuto();
	}

	public void debug() {
		System.out.println("L enc: " + getLeftDistance() + " velo " + getLeftSpeed());
		System.out.println("R enc: " + getRightDistance() + " velo " + getRightSpeed());
		System.out.println("Gyro: " + getAngle()/* getGyroAngle().getDegrees() */);
	}

	public void debugSpeed() {
		System.out.println("L speed " + " actual " + getLeftSpeed());
		System.out.println("R speed " + " actual " + getRightSpeed());

	}

	public void setRight() {
		setWheelVelocity(new DriveSignal(40, 0));
	}

	private void configAuto() {
		rightSparkPID.setP(Constants.K_AUTO_RIGHT_P, 0);
		rightSparkPID.setD(Constants.K_AUTO_RIGHT_D, 0);
		rightSparkPID.setFF(Constants.K_AUTO_RIGHT_F, 0);
		rightSparkPID.setOutputRange(-1, 1);

		leftSparkPID.setP(Constants.K_AUTO_LEFT_P, 0);
		leftSparkPID.setD(Constants.K_AUTO_LEFT_D, 0);
		leftSparkPID.setFF(Constants.K_AUTO_LEFT_F, 0);
		leftSparkPID.setOutputRange(-1, 1);

	}

	private void configHigh() {
		driveMultiplier = Constants.DRIVE_HIGH_SPEED;
	}

	boolean teleopstart = true;

	synchronized public void setTeleop() {
		driveState = DriveState.TELEOP;
	}

	public void calibrateGyro() {
		gyroSensor.calibrate();
	}

	public void printCurrent() {
		System.out.println(leftSpark);
	}

	private void configMotors() {
		leftSparkSlave.follow(leftSpark);
		rightSparkSlave.follow(rightSpark);

		leftSpark.setIdleMode(IdleMode.kCoast);
		rightSpark.setIdleMode(IdleMode.kCoast);
		leftSparkSlave.setIdleMode(IdleMode.kCoast);
		rightSparkSlave.setIdleMode(IdleMode.kCoast);
	}

	public void resetMotionProfile() {
		moveProfiler.reset();
	}

	public double getAngle() {
		return gyroSensor.getAngle();
	}

	public double getDistance() {
		return (getLeftDistance() + getRightDistance()) / 2;
	}

	public Rotation2D getGyroAngle() {
		// -180 through 180
		return Rotation2D.fromDegrees(gyroSensor.getAngle());
	}

	public double getLeftDistance() {
		/*
		 * return leftTalon.getSelectedSensorPosition(0) /
		 * Constants.EncoderTicksPerRotation * Constants.WheelDiameter Math.PI * 22d /
		 * 62d / 3d;
		 */
		return leftSparkEncoder.getPosition() * Constants.WHEEL_DIAMETER * Math.PI * 22d / 62d / 3d;
	}

	public double getRightDistance() {
		return rightSparkEncoder.getPosition() * Constants.WHEEL_DIAMETER * Math.PI * 22d / 62d / 3d;
	}

	public double getSpeed() {
		return (getLeftSpeed() + getRightSpeed()) / 2;
	}

	public double getLeftSpeed() {
		return leftSparkEncoder.getVelocity() * 2 * Math.PI / 60d * Constants.WHEEL_DIAMETER / 2d * 22d / 62d / 3d;
	}

	public double getRightSpeed() {
		return rightSparkEncoder.getVelocity() * 2 * Math.PI / 60d * Constants.WHEEL_DIAMETER / 2d * 22d / 62d / 3d;
	}

	public synchronized void setAutoPath(Path autoPath, boolean isReversed) {
		driveState = DriveState.PUREPURSUIT;
		autonomousDriver = new PurePursuitController(autoPath, isReversed, Constants.TRACK_RADIUS,
				Constants.MIN_PATH_SPEED, Constants.MAX_PATH_SPEED, Constants.MIN_LOOKAHEAD_DISTANCE,
				Constants.MAX_LOOKAHEAD_DISTANCE);
		autonomousDriver.resetTime();
		configAuto();
		// System.out.println("even more bad");
		updatePurePursuit();
	}

	public void setBrakeState(NeutralMode mode) {
	}

	public double getVoltage() {
		return 0;
	}

	private void setWheelPower(DriveSignal setVelocity) {
		leftSpark.set(setVelocity.leftVelocity);
		rightSpark.set(setVelocity.rightVelocity);
	}

	private void setWheelVelocity(DriveSignal setVelocity) {
		if (Math.abs(setVelocity.rightVelocity) > Constants.DRIVE_HIGH_SPEED
				|| Math.abs(setVelocity.leftVelocity) > Constants.DRIVE_HIGH_SPEED) {
			DriverStation.getInstance();
			DriverStation.reportError("Velocity set over " + Constants.DRIVE_HIGH_SPEED + " !", false);
			return;
		}
		// inches per sec to rotations per min
		double leftSetpoint = (setVelocity.leftVelocity) / (2 * Math.PI * Constants.WHEEL_DIAMETER / 2d) * 1
				/ Constants.ENCODER_ROTATIONS_FOR_ONE_WHEEL_ROTATION;
		double rightSetpoint = (setVelocity.rightVelocity) / (2 * Math.PI * Constants.WHEEL_DIAMETER / 2d) * 1
				/ Constants.ENCODER_ROTATIONS_FOR_ONE_WHEEL_ROTATION;
		leftSparkPID.setReference(leftSetpoint, ControlType.kVelocity);
		rightSparkPID.setReference(rightSetpoint, ControlType.kVelocity);
	}

	/**
	 * Update the motor outputs with the given control values.
	 *
	 * @param joyX     horizontal control input
	 * @param joyY     vertical control input
	 * @param throttle throttle control input scaled between 1 and -1 (-.8 is 10 %,
	 *                 0 is 50%, 1.0 is 100%)
	 */
	public void arcadeDrive(double joyX, double joyY, double throttle, boolean fullSpeed) {
		synchronized (this) {
			driveState = DriveState.TELEOP;
		}

		double spdL, spdR, pwrL, pwrR;

		if (!fullSpeed) {
			joyY *= .65;
		} else {
			joyY *= 1;
		}

		// scale from 1 to -1 to 1 to 0
		throttle = (throttle + 1) / 2;

		if (throttle < .3) {
			throttle = .3;
		} else if (throttle > .8) {
			throttle = 1;
		}

		joyY *= throttle;
		joyX *= throttle;

		pwrL = Constants.LEFT_SPEEDSCALAR * RobotMath.clampPosNeg1(joyY - joyX);
		pwrR = Constants.RIGHT_SPEEDSCALAR * RobotMath.clampPosNeg1(joyY + joyX);

		spdL = Constants.DRIVE_HIGH_SPEED * pwrL;
		spdR = Constants.DRIVE_HIGH_SPEED * pwrR;

		setWheelVelocity(new DriveSignal(spdL, spdR));
	}

	@Override
	public void update() {
		// System.out.println("L speed " + getLeftSpeed() + " position x " +
		// RobotTracker.getInstance().getOdometry().translationMat.getX());
		// System.out.println("R speed " + getRightSpeed() + " position y " +
		// RobotTracker.getInstance().getOdometry().translationMat.getY());
		// System.out.println(driveState);
		DriveState snapDriveState;
		synchronized (this) {
			snapDriveState = driveState;
		}
		switch (snapDriveState) {
		case TELEOP:
			break;
		case PUREPURSUIT:
			// System.out.println("bad!");
			updatePurePursuit();
			break;
		case TURN:
			updateTurn();
			break;
		}
	}

	public void setRotation(Rotation2D angle) {
		synchronized (this) {
			wantedHeading = angle;
			driveState = DriveState.TURN;
		}
		configHigh();
	}

	private void updateTurn() {
		double error = wantedHeading.rotateBy(RobotTracker.getInstance().getOdometry().rotationMat.inverse())
				.getDegrees();
		double deltaSpeed;
		// System.out.println(RobotTracker.getInstance().getOdometry().rotationMat.getDegrees());
		// System.out.println("error: " + error);
		deltaSpeed = turnPID.update(error);
		deltaSpeed = Math.copySign(
				NarwhalUtility.coercedNormalize(Math.abs(deltaSpeed), 0, 180, 0, Constants.DRIVE_HIGH_SPEED),
				deltaSpeed);
		if (Math.abs(error) < Constants.MAX_TURN_ERROR && deltaSpeed < Constants.MAX_PID_STOP_SPEED) {
			setWheelVelocity(new DriveSignal(0, 0));
			synchronized (this) {
				driveState = DriveState.DONE;
			}
		} else {
			setWheelVelocity(new DriveSignal(-deltaSpeed, deltaSpeed));
		}
	}

	public void setShiftState(boolean state) {
		configHigh();
	}

	private void updatePurePursuit() {
		Log.info("NEODrive", "Updating Pure Pursuit");
		AutoDriveSignal signal = autonomousDriver.calculate(RobotTracker.getInstance().getOdometry());
		if (signal.isDone) {
			synchronized (this) {
				driveState = DriveState.DONE;
			}
			configHigh();
		}
		// System.out.println("signal l:" + signal.command.leftVelocity + " signal R " +
		// signal.command.rightVelocity);
		setWheelVelocity(signal.command);
	}

	public void resetGyro() {
		gyroSensor.reset();
	}

	public boolean checkSubsystem() {
		configMotors();
		return true;
	}

	synchronized public void stopMovement() {
		leftSpark.set(0);
		rightSpark.set(0);
		leftSparkPID.setReference(0, ControlType.kDutyCycle);
		rightSparkPID.setReference(0, ControlType.kDutyCycle);
		setWheelVelocity(new DriveSignal(0, 0));

		driveState = DriveState.TELEOP;
		resetMotionProfile();
	}

	synchronized public boolean isFinished() {
		return driveState == DriveState.DONE || driveState == DriveState.TELEOP;
	}

	public void clearStickyFaults() {
	}
}
