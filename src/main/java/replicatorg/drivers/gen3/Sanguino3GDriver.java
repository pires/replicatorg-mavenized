/*
 Sanguino3GDriver.java

 This is a driver to control a machine that uses the Sanguino with 3rd Generation Electronics.

 Part of the ReplicatorG project - http://www.replicat.org
 Copyright (c) 2008 Zach Smith

 This program is free software; you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation; either version 2 of the License, or
 (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program; if not, write to the Free Software Foundation,
 Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package replicatorg.drivers.gen3;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Vector;
import java.util.logging.Level;

import org.w3c.dom.Node;

import replicatorg.app.Base;
import replicatorg.drivers.DriverError;
import replicatorg.drivers.MultiTool;
import replicatorg.drivers.OnboardParameters;
import replicatorg.drivers.PenPlotter;
import replicatorg.drivers.RetryException;
import replicatorg.drivers.SDCardCapture;
import replicatorg.drivers.SerialDriver;
import replicatorg.drivers.Version;
import replicatorg.drivers.gen3.PacketProcessor.CRCException;
import replicatorg.machine.model.AxisId;
import replicatorg.machine.model.ToolModel;
import replicatorg.uploader.FirmwareUploader;
import replicatorg.util.Point5d;

import replicatorg.drivers.gen3.EEPROMClass;

class Sanguino3GEEPRPOM implements EEPROMClass {

	public static final int EEPROM_CHECK_LOW = 0x5A;
	public static final int EEPROM_CHECK_HIGH = 0x78;

	// / EEPROM map:
	// / 00-01 - EEPROM data version
	// / 02 - Axis inversion byte
	// / 32-47 - Machine name (max. 16 chars)
	final public static int EEPROM_CHECK_OFFSET = 0;
	final public static int EEPROM_AXIS_INVERSION_OFFSET = 2;
	final public static int EEPROM_ENDSTOP_INVERSION_OFFSET = 3;
	final public static int EEPROM_MACHINE_NAME_OFFSET = 32;
	final public static int EEPROM_AXIS_HOME_POSITIONS_OFFSET = 96;
	final public static int EEPROM_ESTOP_CONFIGURATION_OFFSET = 116;

	final static class ECThermistorOffsets {
		final public static int[] TABLE_OFFSETS = { 0x00f0, 0x0170 };

		final public static int R0 = 0x00;
		final public static int T0 = 0x04;
		final public static int BETA = 0x08;
		final public static int DATA = 0x10;

		public static int r0(int which) {
			return R0 + TABLE_OFFSETS[which];
		}

		public static int t0(int which) {
			return T0 + TABLE_OFFSETS[which];
		}

		public static int beta(int which) {
			return BETA + TABLE_OFFSETS[which];
		}

		public static int data(int which) {
			return DATA + TABLE_OFFSETS[which];
		}
	};

	final public static int EC_EEPROM_EXTRA_FEATURES = 0x0018;
	final public static int EC_EEPROM_SLAVE_ID = 0x001A;

	final public static int MAX_MACHINE_NAME_LEN = 16;
}

public class Sanguino3GDriver extends SerialDriver implements
		OnboardParameters, SDCardCapture, PenPlotter, MultiTool {
	protected final static int DEFAULT_RETRIES = 5;

	Version toolVersion = new Version(0, 0);

	private boolean eepromChecked = false;
        
        protected boolean acceleratedFirmware = false;

	public Sanguino3GDriver() {
		super();
		hasEmergencyStop = true;
		hasSoftStop = true;

		// Make sure this accurately reflects what versions this supports
		minimumVersion = new Version(3, 0);
		preferredVersion = new Version(3, 0);
		// init our variables.
		setInitialized(false);
	}

	public void loadXML(Node xml) {
		super.loadXML(xml);

	}

	public void initialize() {
		Base.logger.fine("Attempting to initialize device");

		// Assert: serial port present.
		assert serial != null : "No serial port found.";
		// wait till we're initialized
		if (!isInitialized()) {
			// attempt to send version command and retrieve reply.
			try {
				// Default timeout should be 2.6s. Timeout can be sped up for
				// v2, but let's play it safe.
				int timeout = 2600;
				connectToDevice(timeout);
			} catch (Exception e) {
				// todo: handle init exceptions here
				e.printStackTrace();
			}
		}

		// did it actually work?
		if (isInitialized()) {
			// okay, take care of version info /etc.
			if (version.compareTo(getMinimumVersion()) < 0) {
				Base.logger.log(Level.WARNING,
                            "\n********************************************************\n"
                            + "This version of ReplicatorG is not reccomended for use with firmware before version "
                            + getMinimumVersion()
                            + ". Either update your firmware or proceed with caution.\n"
                            + "********************************************************");
			}
			sendInit();
			super.initialize();
			// dial down timeout for accelerated firmware so that we can refill
			// firmware command buffer as quickly as possible
			if(acceleratedFirmware){
				serial.setTimeout(200);
			}
			invalidatePosition();

			return;
		} else {
			Base.logger.info("Unable to connect to firmware.");
			// Dispose of driver to free up any resources
			dispose();
		}
	}
	
	public boolean initializeBot()
	{
		// Scan for each slave
		for (ToolModel t : getMachine().getTools()) {
			if (t != null) {
				initSlave(t.getIndex());
			}
		}
	return true;

	}
	
	private boolean attemptConnection() {
		// Eat anything in the serial buffer
		serial.clear();

		version = getVersionInternal();
		if (version != null){
			boolean initOk = initializeBot();
			if(initOk == false) {
				setInitialized(false);
				return false;
			}
			
			final String MB_NAME = "RepRap Motherboard v1.X";
			FirmwareUploader.checkLatestVersion(MB_NAME, version);

			// If we're dealing with older firmware, set timeout to infinity
			if (version.getMajor() < 2) {
				serial.setTimeout(Integer.MAX_VALUE);
			}
			setInitialized(true);
		}
		return isInitialized();
	}

	public void assessState() {
		// If we are supposed to have a serial connection, see if it is still
		// active
		if (isInitialized() && !serial.isConnected()) {
			setError("Serial disconnected");
			setInitialized(false);
		}
	}

	/**
	 * Connect to the device. After the specified timeout, replicatorG will
	 * attempt to remotely reset the device.
	 * @timeoutMillis the time, in milliseconds, that we should wait for a
	 *                handshake.
	 * @return true if we received a handshake; false if we timed out.
	 */
	protected void connectToDevice(int timeoutMillis) {
		assert (serial != null);
		synchronized (serial) {
			serial.clear();
			serial.setTimeout(timeoutMillis);
			if (attemptConnection())
				return;
			// Timed out waiting. It's possible that a reset was triggered when
			// the device
			// was opened, since RXTX doesn't allow control of default RTS
			// states.
			// Wait >2.6s -- 2s for the arduino reset; .6 seconds for the rest
			// of the
			// system to come up.
			try {
				Thread.sleep(timeoutMillis);
			} catch (InterruptedException ie) {
				// Assume we're shutting down the app or aborting the
				// attempt. Reassert interrupted status and let
				// the thread wind down.
				Thread.currentThread().interrupt();
				return;
			}
			if (attemptConnection())
				return;
			// Timed out again. It is possible that the machine is in a bad
			// state.
			Base.logger
					.warning("No connection; trying to pulse RTS to reset device.");
			serial.pulseRTSLow();
			// Wait >2.6s -- 2s for the arduino reset; .6 seconds for the rest
			// of the
			// system to come up.
			try {
				Thread.sleep(timeoutMillis);
			} catch (InterruptedException ie) {
				// Assume we're shutting down the app or aborting the
				// attempt. Reassert interrupted status and let
				// the thread wind down.
				Thread.currentThread().interrupt();
				return;
			}
			// One last attempt, post reset
			attemptConnection();
		}
	}

	/**
	 * Sends the command over the serial connection and retrieves a result.
	 */
	protected PacketResponse runCommand(byte[] packet) throws RetryException {
		return runCommand(packet, DEFAULT_RETRIES);
	}

	protected PacketResponse runQuery(byte[] packet, int retries) {
		try {
			return runCommand(packet, retries);
		} catch (RetryException re) {
			throw new RuntimeException("Queries can not have valid retries!");
		}
	}

	protected PacketResponse runQuery(byte[] packet) {
		return runQuery(packet, 1);
	}

	//// Get a list of all toolheads we save onboard preferences for 
	public List<Integer> toolheadsWithStoredData()
	{
		Vector<ToolModel> tools = this.getMachine().getTools();
		Vector<Integer> toolsList = new Vector<Integer>();
		for( ToolModel t : tools )
		{
			toolsList.add(new Integer(t.getIndex()));
		}
		return toolsList;
	}

	
	void printDebugData(String title, byte[] data) {
		StringBuffer buf = new StringBuffer(title + ": ");
		for (int i = 0; i < data.length; i++) {
			buf.append(Integer.toHexString((int) data[i] & 0xff));
			buf.append(" ");
		}
		Base.logger.finer(buf.toString());
	}

	/**
	 * It's important here to understand the difference between "retries" and
	 * the retry exception. A retry is called when packet transmission itself
	 * failed and we want to try again. The retry exception is thrown when the
	 * packet was successfully processed, but the buffer was full, indicating to
	 * the controller that another attempt is warranted.
	 * 
	 * If the specified number of retries is negative, the packet will be tried
	 * -N times, and no logging message will be displayed when the packet times
	 * out. This is for "unreliable" packets (ordinarily, when scanning for
	 * toolheads).
	 * 
	 * @param packet
	 * @param retries
	 * @return
	 * @throws RetryException
	 */
	protected PacketResponse runCommand(byte[] packet, int retries)
			throws RetryException {

		if (retries == 0) {
			Base.logger.severe("Packet timed out!");
			return PacketResponse.timeoutResponse();
		}
		if (packet == null || packet.length < 4) {
			Base.logger.severe("Attempt to send empty or too-small packet");
			return null; // skip empty commands or broken commands
		}

		boolean isCommand = (packet[2] & 0x80) != 0;
		
		if (fileCaptureOstream != null) {
			// capture to file.
			try {
				if (isCommand) { // ignore query commands
					fileCaptureOstream.write(packet, 2, packet.length - 3);
				}
			} catch (IOException ioe) {
				// IOE should be very rare and shouldn't have to contaminate
				// our whole call stack; we'll wrap it in a runtime error.
				throw new RuntimeException(ioe);
			}
			return PacketResponse.okResponse(); // Always pretend that it's all
												// good.
		}

		// This can actually happen during shutdown.
		if (serial == null)
			return PacketResponse.timeoutResponse();

		PacketProcessor pp;
		PacketResponse pr = new PacketResponse();

		assert (serial != null);

		synchronized (serial) {

			// Dump out if interrupted
			if (Thread.currentThread().isInterrupted()) {
				// Clear interrupted status
				Thread.interrupted();
				// Wait for end of packet and clear (if forthcoming)
				try {
					Thread.sleep(10);
					serial.clear();
				} catch (InterruptedException e) {
					// safe to ignore
				}
				// Reestablish interrupt
				Thread.currentThread().interrupt();
				return pr;
			}

			pp = new PacketProcessor();
			
			if(packet == null) {
				Base.logger.severe("null packet in runCommand");
				return PacketResponse.timeoutResponse();
			}
			
			// This can actually happen during shutdown.
			if (serial == null){
				Base.logger.severe("null serial in runCommand");
				return PacketResponse.timeoutResponse();
			}
			// Do not allow a stop or reset command to interrupt mid-packet!
			serial.write(packet);
			
			printDebugData("OUT", packet);

			// Read entire response packet
			boolean completed = false;
			while (!completed) {
				// Dump out if interrupted
				int b = serial.read();
				if (b == -1) {
					if (Thread.currentThread().isInterrupted()) {
						break;
					}
					if (retries > 1) {
                                            
                                            // accelerated Firmware has a low timeout period and times out frequently
                                            // dial down timeout logging because there will be a LOT of it
                                            if(acceleratedFirmware){
						Base.logger.finest("Read timed out; retries remaining: "
										+ Integer.toString(retries));
                                            }
                                            else{
                                                Base.logger.severe("Read timed out; retries remaining: "
										+ Integer.toString(retries));
                                            }
					}
					if (retries == -1) {
						// silently return a timeout response
						return PacketResponse.timeoutResponse();
					} else if (retries < 0) {
						return runCommand(packet, retries + 1);
					}
					return runCommand(packet, retries - 1);
				}
				try {
					completed = pp.processByte((byte) b);
				} catch (CRCException e) {
					Base.logger.severe("Bad CRC received; retries remaining: "
							+ Integer.toString(retries));
					return runCommand(packet, retries - 1);
				}
			}
			pr = pp.getResponse();

			if (pr.isOK()) {
				// okay!
			} else if (pr.getResponseCode() == PacketResponse.ResponseCode.BUFFER_OVERFLOW) {
				throw new RetryException();
			} else if (pr.getResponseCode() == PacketResponse.ResponseCode.CANCEL){
				Base.getEditor().handleStop(); ///  horrible horrible 
				Base.logger.severe("Build Canceled by Printer");
			} else {
				// Other random error
				printDebugData("Unknown error sending, retry", packet);
				if (retries > 1) {
					return runCommand(packet, retries - 1);
				}
			}
		}
		return pr;
	}

	static boolean isNotifiedFinishedFeature = false;

	public boolean isFinished() {
		if (fileCaptureOstream != null) {
			return true;
		} // always done instantly if writing to file
		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.IS_FINISHED.getCode());
		PacketResponse pr = runQuery(pb.getPacket());
		if (!pr.isOK()) {
			return false;
		}
		int v = pr.get8();
		if (pr.getResponseCode() == PacketResponse.ResponseCode.UNSUPPORTED) {
			if (!isNotifiedFinishedFeature) {
				Base.logger.severe("IsFinished not supported by this firmware. " +
						"Update your firmware.");
				isNotifiedFinishedFeature = true;
			}
			return true;
		}
		boolean finished = (v != 0);
		Base.logger.fine("Is finished: " + Boolean.toString(finished));
		return finished;
	}

	
	
	public boolean isBufferEmpty() {
		// TODO: Make sure this is right
		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.IS_FINISHED.getCode());
		PacketResponse pr = runQuery(pb.getPacket());
		if (!pr.isOK()) {
			return false;
		}
		int v = pr.get8();
		if (pr.getResponseCode() == PacketResponse.ResponseCode.UNSUPPORTED) {
			if (!isNotifiedFinishedFeature) {
				Base.logger.severe("IsFinished not supported by this firmware. " +
						"Update your firmware.");
				isNotifiedFinishedFeature = true;
			}
			return true;
		}
		boolean finished = (v != 0);
		Base.logger.fine("Buffer empty: " + Boolean.toString(finished));
		return finished;
	}

	public void dispose() {
		super.dispose();
	}

	/***************************************************************************
	 * commands used internally to driver
	 **************************************************************************/
	
	/** 
	 * Make an inital connection to read the version code.
	 * @return
	 */
	public Version getVersionInternal() {
		
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.VERSION.getCode());
		pb.add16(Base.VERSION);

		PacketResponse pr = runQuery(pb.getPacket(), 1);
		if (pr.isEmpty() || !pr.isOK())
			return null;
		int versionNum = pr.get16();

		pb = new PacketBuilder(MotherboardCommandCode.GET_BUILD_NAME.getCode());
		pb.add16(Base.VERSION);

		String buildname = "";
		pr = runQuery(pb.getPacket(), 1);
		if (!pr.isEmpty() && pr.isOK()) {
			byte[] payload = pr.getPayload();
			byte[] subarray = new byte[payload.length - 1];
			System.arraycopy(payload, 1, subarray, 0, subarray.length);
			buildname = " (" + new String(subarray) + ")";
		}

		Base.logger.fine("Reported version: " + versionNum + " " + buildname);
		if (versionNum == 0) {
			Base.logger.severe("Null version reported!");
			return null;
		}
		
		Version v = new Version(versionNum / 100, versionNum % 100);
		Base.logger.warning("Motherboard firmware v" + v + buildname);
		return v;
	}


	public CommunicationStatistics getCommunicationStatistics() {
		CommunicationStatistics stats = new CommunicationStatistics();

		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.GET_COMMUNICATION_STATS.getCode());

		PacketResponse pr = runQuery(pb.getPacket(), 1);
		if (pr.isEmpty())
			return null;
		stats.packetCount = pr.get32();
		stats.sentPacketCount = pr.get32();
		stats.packetFailureCount = pr.get32();
		stats.packetRetryCount = pr.get32();
		stats.noiseByteCount = pr.get32();

		return stats;
	}

	public void initSlave(int toolhead) {
		
		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();

		PacketBuilder slavepb = new PacketBuilder(
				MotherboardCommandCode.TOOL_QUERY.getCode());
		slavepb.add8((byte) toolhead);
		slavepb.add8(ToolCommandCode.VERSION.getCode());
		int slaveVersionNum = 0;
		PacketResponse slavepr = runQuery(slavepb.getPacket(), -2);
		if (!slavepr.isEmpty()) {
			slaveVersionNum = slavepr.get16();
		}

		slavepb = new PacketBuilder(MotherboardCommandCode.TOOL_QUERY.getCode());
		slavepb.add8((byte) toolhead);
		slavepb.add8(ToolCommandCode.GET_BUILD_NAME.getCode());
		slavepr = runQuery(slavepb.getPacket(), -2);

		String buildname = "";
		slavepr = runQuery(slavepb.getPacket(), 1);
		if (!slavepr.isEmpty()) {
			byte[] payload = slavepr.getPayload();
			if (payload.length > 0) {
				byte[] subarray = new byte[payload.length - 1];
				System.arraycopy(payload, 1, subarray, 0, subarray.length);
				buildname = " (" + new String(subarray) + ")";
			}
		}

		Base.logger.fine("Reported slave board version: " + slaveVersionNum
				+ " " + buildname);
		if (slaveVersionNum == 0) {
			String message = "Toolhead "
					+ Integer.toString(toolhead)
					+ ": Not found.\nMake sure the toolhead is connected, " +
					"the power supply is plugged in and turned on, and the " +
					"power switch on the motherboard is on.";

			setError(new DriverError(message, false));
			Base.logger.severe(message);
		} else {
			Version sv = new Version(slaveVersionNum / 100,
					slaveVersionNum % 100);
			toolVersion = sv;
			Base.logger.warning("Toolhead " + Integer.toString(toolhead)
					+ ": Extruder controller firmware v" + sv + buildname);

			final String EC_NAME = "Extruder Controller v2.2";
			FirmwareUploader.checkLatestVersion(EC_NAME, sv);
		}
		
		ToolModel curToolMod = getMachine().getTool(toolhead);
		if (curToolMod != null) {
			if(curToolMod.motorIsStepper()) {
				double targetRPM =  curToolMod.getMotorSpeedRPM();
				///set 'running RPM' to be the same as the default RPM
				try { 
					this.setMotorRPM( targetRPM, toolhead );
				}
				catch (replicatorg.drivers.RetryException e)
				{
					Base.logger.severe("could not init motor RPM, got exception" + e );
				}
			}
			else {
				int targetPWM =  curToolMod.getMotorSpeedPWM();
				///set 'running PWM' to be the same as the default PWM
				try { 
					this.setMotorSpeedPWM( targetPWM, toolhead );
				}
				catch (replicatorg.drivers.RetryException e)
				{
					Base.logger.severe("could not init motor RPM, got exception" + e );
				}
			}
		}
//		//TRICKY: this is just called to get the value cached into the ToolModel
//		double ignore = getMotorRPM(toolIndex);
	

	}

	public void sendInit() {
		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.INIT.getCode());
		runQuery(pb.getPacket());
	}

	/***************************************************************************
	 * commands for interfacing with the driver directly
	 * 
	 * @throws RetryException
	 **************************************************************************/

	public void queuePoint(Point5d p) throws RetryException {
		// TODO: check if our current position is valid?

		Base.logger.finer("Queued point " + p);

		// is this point even step-worthy?
		Point5d deltaSteps = getAbsDeltaSteps(getCurrentPosition(false), p);
		double masterSteps = getLongestLength(deltaSteps);

		// okay, we need at least one step.
		if (masterSteps > 0.0) {
			// where we going?
			Point5d steps = machine.mmToSteps(p);

			Point5d delta = getDelta(p);
			double feedrate = getSafeFeedrate(delta);

			// how fast are we doing it?
			long micros = convertFeedrateToMicros(getCurrentPosition(false), p,	feedrate);

			// System.err.println("Steps :"+steps.toString()+" micros "+Long.toString(micros));

			// okay, send it off!
			queueAbsolutePoint(steps, micros);

			super.queuePoint(p);
		}
	}

	// public Point3d getPosition() {
	// return new Point3d();
	// }

	/*
	 * //figure out the axis with the most steps. Point3d steps =
	 * getAbsDeltaSteps(getCurrentPosition(), p); Point3d delta_steps =
	 * getDeltaSteps(getCurrentPosition(), p); int max = Math.max((int)steps.x,
	 * (int)steps.y); max = Math.max(max, (int)steps.z);
	 * 
	 * //get the ratio of steps to take each segment double xRatio = steps.x /
	 * max; double yRatio = steps.y / max; double zRatio = steps.z / max;
	 * 
	 * //how many segments will there be? int segmentCount = (int)Math.ceil(max
	 * / 32767.0);
	 * 
	 * //within our range? just do it. if (segmentCount == 1)
	 * queueIncrementalPoint(pb, delta_steps, ticks); else { for (int i=0;
	 * i<segmentCount; i++) { Point3d segmentSteps = new Point3d();
	 * 
	 * //TODO: is this accurate? //TODO: factor in negative deltas! //calculate
	 * our line segments segmentSteps.x = Math.round(32767 * xRatio);
	 * segmentSteps.y = Math.round(32767 * yRatio); segmentSteps.z =
	 * Math.round(32767 * zRatio);
	 * 
	 * //keep track of them. steps.x -= segmentSteps.x; steps.y -=
	 * segmentSteps.y; steps.z -= segmentSteps.z;
	 * 
	 * //send this segment queueIncrementalPoint(pb, segmentSteps, ticks); } }
	 */
	protected void queueAbsolutePoint(Point5d steps, long micros)
			throws RetryException {
		
		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.QUEUE_POINT_ABS.getCode());

		Base.logger.fine("Queued absolute point " + steps + " at "
				+ Long.toString(micros) + " usec.");

		// just add them in now.
		pb.add32((int) steps.x());
		pb.add32((int) steps.y());
		pb.add32((int) steps.z());
		pb.add32((int) micros);

		runCommand(pb.getPacket());
	}

	public void setCurrentPosition(Point5d p) throws RetryException {
		// System.err.println("   SCP: "+p.toString()+
		// " (current "+getCurrentPosition().toString()+")");
		// if (super.getCurrentPosition().equals(p)) return;
		// System.err.println("COMMIT: "+p.toString()+
		// " (current "+getCurrentPosition().toString()+")");
		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.SET_POSITION.getCode());

		Point5d steps = machine.mmToSteps(p);
		pb.add32((long) steps.x());
		pb.add32((long) steps.y());
		pb.add32((long) steps.z());

		Base.logger.fine("Set current position to " + p + " (" + steps + ")");

		runCommand(pb.getPacket());

		super.setCurrentPosition(p);
	}

	/// TODO: this says it homes the first three axes, but it actually homes
	/// whatever's passed
	/// Homes the three first axes
	public void homeAxes(EnumSet<AxisId> axes, boolean positive, double feedrate)
			throws RetryException {
		Base.logger.fine("Homing axes " + axes.toString());
		byte flags = 0x00;
		double timeout = 0;

		Point5d homingFeedrates = machine.getHomingFeedrates();
		Point5d timeOut = machine.getTimeOut();

		if (feedrate <= 0) {
			// figure out our fastest feedrate.
			feedrate = 0;
			for (AxisId axis : machine.getAvailableAxes()) {
				feedrate = Math.max(homingFeedrates.axis(axis), feedrate);
			}
		}

		Point5d target = new Point5d();

		for (AxisId axis : axes) {
			flags += 1 << axis.getIndex();
			feedrate = Math.min(feedrate, homingFeedrates.axis(axis));
			timeout = Math.max(timeout, timeOut.axis(axis));
			target.setAxis(axis, 1);
		}

		// calculate ticks
		long micros = convertFeedrateToMicros(new Point5d(), target, feedrate);
		// send it!
		int code = positive ? MotherboardCommandCode.FIND_AXES_MAXIMUM
				.getCode() : MotherboardCommandCode.FIND_AXES_MINIMUM.getCode();
		PacketBuilder pb = new PacketBuilder(code);
		pb.add8(flags);
		pb.add32((int) micros);
		pb.add16((int) timeout);
		runCommand(pb.getPacket());

		invalidatePosition();
	}

	public void delay(long millis) throws RetryException {
		Base.logger.finer("Delaying " + millis + " millis.");
		Base.logger.fine("Sanguino3GDriver.enableMotor()");//REMOVE:

		// send it!
		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.DELAY.getCode());
		pb.add32(millis);
		runCommand(pb.getPacket());
	}

	public void openClamp(int clampIndex) {
		// TODO: throw some sort of unsupported exception.
		super.openClamp(clampIndex);
	}

	public void closeClamp(int clampIndex) {
		// TODO: throw some sort of unsupported exception.
		super.closeClamp(clampIndex);
	}

	public void enableDrives() throws RetryException {
		// Command RMB to enable its steppers. Note that they are
		// already automagically enabled by most commands and need
		// not be explicitly enabled.
		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.ENABLE_AXES.getCode());
		pb.add8(0x9f); // enable all 5 axes
		runCommand(pb.getPacket());
		super.enableDrives();
	}

	public void disableDrives() throws RetryException {
		// Command RMB to disable its steppers.
		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.ENABLE_AXES.getCode());
		pb.add8(0x1f); // disable all 5 axes
		runCommand(pb.getPacket());
		super.disableDrives();
	}

	/**
	 * Convert a set of axes to a bitfield by index. For example, axes X and Z
	 * would map to "5".
	 * 
	 * @param axes
	 *            an enumset of axis to construct a bitfield for
	 * @return an integer with a bit set corresponding to each axis in the input
	 *         set
	 */
	private int axesToBitfield(EnumSet<AxisId> axes) {
		int v = 0;
		for (AxisId axis : axes) {
			v += 1 << axis.getIndex();
		}
		return v;
	}

	/** Command machine to enable some steppers. Note that they are
	* already automagically enabled by most commands and need
	* not be explicitly enabled.
	*/
	public void enableAxes(EnumSet<AxisId> axes) throws RetryException {
		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.ENABLE_AXES.getCode());
		pb.add8(0x80 + (axesToBitfield(axes) & 0x1f)); // enable axes
		runCommand(pb.getPacket());
		super.enableAxes(axes);
	}

	/// Command machine to disable some steppers.
	public void disableAxes(EnumSet<AxisId> axes) throws RetryException {
		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.ENABLE_AXES.getCode());
		pb.add8(axesToBitfield(axes) & 0x1f); // disable axes
		runCommand(pb.getPacket());
		super.disableAxes(axes);
	}

	public void changeGearRatio(int ratioIndex) {
		// TODO: throw some sort of unsupported exception.
		super.changeGearRatio(ratioIndex);
	}

	/**
	 * Will wait for first the tool, then the build platform, it exists and
	 * supported. Technically the platform is connected to a tool (extruder
	 * controller) but this information is currently not used by the firmware.
	 * 
	 * timeout is given in seconds. If the tool isn't ready by then, the machine
	 * will continue anyway.
	 */
	public void requestToolChange(int toolhead, int timeout)
			throws RetryException {

		selectTool(toolhead);

		Base.logger.fine("Waiting for tool #" + toolhead);

		// send it!
		if (this.machine.getTool(toolhead).getTargetTemperature() > 0.0) {
			PacketBuilder pb = new PacketBuilder(
					MotherboardCommandCode.WAIT_FOR_TOOL.getCode());
			pb.add8((byte) toolhead);
			pb.add16(100); // delay between master -> slave pings (millis)
			pb.add16(timeout); // timeout before continuing (seconds)
			runCommand(pb.getPacket());
		}

		
		// FIXME: We used to check for version here, but this will only work if
		// we're connected. Atm., we'll rather
		// require the latest firmware.
		// getVersion().atLeast(new Version(2,4)) && toolVersion.atLeast(new
		// Version(2,6))
		if (this.machine.getTool(toolhead) != null
				&& this.machine.getTool(toolhead).hasHeatedPlatform()
				&& this.machine.getTool(toolhead).getPlatformTargetTemperature() > 0.0) {
			PacketBuilder pb = new PacketBuilder(
					MotherboardCommandCode.WAIT_FOR_PLATFORM.getCode());
			pb.add8((byte) toolhead);
			pb.add16(100); // delay between master -> slave pings (millis)
			pb.add16(timeout); // timeout before continuing (seconds)
			runCommand(pb.getPacket());
		}
	}

	
	public void selectTool(int toolIndex) throws RetryException {
		Base.logger.fine("Selecting tool #" + toolIndex);

		// send it!
		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.CHANGE_TOOL.getCode());
		pb.add8((byte) toolIndex);
		runCommand(pb.getPacket());

		super.selectTool(toolIndex);
	}

	
	/***************************************************************************
	 * Motor interface functions
	 **************************************************************************/
	public void setMotorRPM(double rpm, int toolhead) throws RetryException {

		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();
		// convert RPM into microseconds and then send.
		long microseconds = rpm == 0 ? 0 : Math.round(60.0 * 1000000.0 / rpm); // no
		// unsigned
		// ints?!?
		// microseconds = Math.min(microseconds, 2^32-1); // limit to uint32.

		Base.logger.fine("Setting motor 1 speed to " + rpm + " RPM ("
				+ microseconds + " microseconds)");

		// send it!
		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.TOOL_COMMAND.getCode());
		pb.add8((byte) toolhead );
		pb.add8(ToolCommandCode.SET_MOTOR_1_RPM.getCode());
		pb.add8((byte) 4); // length of payload.
		pb.add32(microseconds);
		runCommand(pb.getPacket());

		//TRICKY: WAS 'super.setMotorRPM(rpm);', but this seems not to work right.
		// Seems to set default motor value(motorSpeedRPM , not 'running' motor
		// value. Caused gui to show bad values
//		machine.getTool(toolhead).setMotorSpeedReadingRPM(rpm);
//		Changed back - Ted
		super.setMotorRPM(rpm, toolhead);
	}

	
	public void setMotorSpeedPWM(int pwm) throws RetryException {
		this.setMotorSpeedPWM(pwm, machine.currentTool().getIndex());
	}
	
	
	public void setMotorSpeedPWM(int pwm, int toolhead) throws RetryException {
	
		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();

		// If we are using a relay, make sure that we don't enable the PWM
		if (machine.getTool(toolhead).getMotorUsesRelay() && pwm > 0) {
			Base.logger.fine("Tool motor uses relay, overriding PWM setting");
			pwm = 255;
		}

		Base.logger.fine("Setting motor 1 speed to " + pwm + " PWM");

		// send it!
		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.TOOL_COMMAND.getCode());
		pb.add8((byte) toolhead);
		pb.add8(ToolCommandCode.SET_MOTOR_1_PWM.getCode());
		pb.add8((byte) 1); // length of payload.
		pb.add8((byte) ((pwm > 255) ? 255 : pwm));
		runCommand(pb.getPacket());

		super.setMotorSpeedPWM(pwm, toolhead);
	}

	@Deprecated
	public void enableMotor() throws RetryException {
		/// toolhead -1 indicate auto-detect
		this.enableMotor(-1);
	}
	
	public void enableMotor(int toolhead) throws RetryException {

		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 )
			toolhead = machine.currentTool().getIndex();

		// our flag variable starts with motors enabled.
		byte flags = 1;

		// bit 1 determines direction...
		if (machine.getTool(toolhead).getMotorDirection() == ToolModel.MOTOR_CLOCKWISE)
			flags += 2;

		Base.logger.fine("Toggling motor 1 w/ flags: " + Integer.toBinaryString(flags));

		// send it!
		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.TOOL_COMMAND.getCode());
		pb.add8((byte) toolhead );
		pb.add8(ToolCommandCode.TOGGLE_MOTOR_1.getCode());
		pb.add8((byte) 1); // payload length
		pb.add8(flags);
		runCommand(pb.getPacket());

		super.enableMotor(toolhead);
	}

	@Override
	public void disableMotor(int toolhead) throws RetryException {

		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();

		// bit 1 determines direction...
		byte flags = 0;
		if (machine.getTool(toolhead).getSpindleDirection() == ToolModel.MOTOR_CLOCKWISE)
			flags += 2;

		Base.logger.finer("Disabling motor 1");

		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.TOOL_COMMAND.getCode());
		pb.add8((byte) toolhead );
		pb.add8(ToolCommandCode.TOGGLE_MOTOR_1.getCode());
		pb.add8((byte) 1); // payload length
		pb.add8(flags);
		runCommand(pb.getPacket());

		super.disableMotor(toolhead);
	}

	@Deprecated
	public int getMotorSpeedPWM() {
		return this.getMotorSpeedPWM(machine.currentTool().getIndex());
	}
	public int getMotorSpeedPWM(int toolhead) {

		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();

		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.TOOL_QUERY.getCode());
		pb.add8((byte) toolhead);
		pb.add8(ToolCommandCode.GET_MOTOR_1_PWM.getCode());
		PacketResponse pr = runQuery(pb.getPacket());

		pr.printDebug();
		// get it
		int pwm = pr.get8();

		Base.logger.fine("Current motor 1 PWM: " + pwm);

		// set it.
		machine.getTool(toolhead).setMotorSpeedReadingPWM(pwm);

		return pwm;
	}

	public double getMotorRPM()
	{
		return getMotorRPM(machine.currentTool().getIndex());
	}
	
	public double getMotorRPM(int toolhead) {
		
		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();

		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.TOOL_QUERY.getCode());
		pb.add8((byte)toolhead);
		pb.add8(ToolCommandCode.GET_MOTOR_1_RPM.getCode());
		PacketResponse pr = runQuery(pb.getPacket());

		// convert back to RPM
		long micros = pr.get32();
		double rpm = 0;
		if (micros > 0)
			rpm = (60.0 * 1000000.0 / micros);

		Base.logger.fine("Current motor 1 RPM: " + rpm + " (" + micros + ")");

		// set it.
		machine.getTool(toolhead).setMotorSpeedReadingRPM(rpm);

		return rpm;
	}

	
	@Deprecated
	public void readToolStatus() {
		this.readToolStatus(machine.currentTool().getIndex());
	}
	
	public void readToolStatus(int toolhead) {

		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();

		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.TOOL_QUERY.getCode());
		pb.add8((byte) toolhead );
		pb.add8(ToolCommandCode.GET_TOOL_STATUS.getCode());
		PacketResponse pr = runQuery(pb.getPacket());
		if (pr.isEmpty())
			return;
		// FIXME: First, check that the result code is OK. We occasionally
		// receive RC_DOWNSTREAM_TIMEOUT codes here. kintel 20101207.
		int status = pr.get8();
		machine.getTool(toolhead).setToolStatus(status);

		if (Base.logger.isLoggable(Level.FINE)) {
			Base.logger.fine("Extruder Status: " + status + ": "
					+ (((status & 0x80) != 0) ? "EXTRUDER_ERROR " : "")
					+ (((status & 0x40) != 0) ? "PLATFORM_ERROR " : "")
					+ (((status & 0x20) != 0) ? "WDRF " : "")
					+ (((status & 0x10) != 0) ? "BORF " : "")
					+ (((status & 0x08) != 0) ? "EXTRF " : "")
					+ (((status & 0x04) != 0) ? "PORF " : "")
					+ (((status & 0x01) != 0) ? "READY" : "NOT READY") + " ");
		}

		readToolPIDState();
	}

	// TODO: This doesn't belong here
	private int fixSigned(int value) {
		if (value >= 1 << 15) {
			value = value - (1 << 16);
		}

		return value;
	}

	@Deprecated 
	public void readToolPIDState() {
		this.readToolPIDState(machine.currentTool().getIndex());
	}
	// TODO: Implement a way for this to reach the outside
	public void readToolPIDState(int toolhead) {

		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();

		if (Base.logger.isLoggable(Level.FINE)) {

			PacketBuilder pb = new PacketBuilder(
					MotherboardCommandCode.TOOL_QUERY.getCode());
			pb.add8((byte) toolhead );
			pb.add8(ToolCommandCode.GET_PID_STATE.getCode());
			PacketResponse pr = runQuery(pb.getPacket());
			if (pr.isEmpty())
				return;
			// FIXME: First, check that the result code is OK. We occasionally
			// receive RC_DOWNSTREAM_TIMEOUT codes here. kintel 20101207.

			int extruderErrorTerm = fixSigned((int) pr.get16());
			int extruderDeltaTerm = fixSigned((int) pr.get16());
			int extruderLastOutput = fixSigned((int) pr.get16());

			int platformErrorTerm = fixSigned((int) pr.get16());
			int platformDeltaTerm = fixSigned((int) pr.get16());
			int platformLastOutput = fixSigned((int) pr.get16());

			Base.logger.fine("Extuder PID State:" + "  error: "
					+ extruderErrorTerm + "  delta: " + extruderDeltaTerm
					+ "  output: " + extruderLastOutput);
			Base.logger.fine("Platform PID State:" + "  error: "
					+ platformErrorTerm + "  delta: " + platformDeltaTerm
					+ "  output: " + platformLastOutput);
		}
	}

	/***************************************************************************
	 * PenPlotter interface functions
	 * 
	 * @throws RetryException
	 **************************************************************************/
	// public void moveServo(int degree) {}

	// public void enableServo() {}

	// public void disableServo() {}

	@Deprecated
	public void setServoPos(int index, double degree) throws RetryException {
		this.setServoPos(index, degree, machine.currentTool().getIndex());
	}
	
	public void setServoPos(int index, double degree, int toolhead) throws RetryException {
		
		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();

		int command = 0;

		// We can handle two servos
		if (index == 0) {
			command = ToolCommandCode.SET_SERVO_1_POS.getCode();
		} else if (index == 1) {
			command = ToolCommandCode.SET_SERVO_2_POS.getCode();
		} else {
			// throw?
			Base.logger.severe("Servo index " + index + " not supported, ignoring");
			return;
		}

		// We accept from 0 - 180 degrees. It is noted that some older servos
		// can only handle 90 degrees of rotation;
		// for them, use 45-135. To turn the servo off, use 255.
		if (degree == 255) {
			// thats ok.
		} else if (degree < 0) {
			degree = 0;
		} else if (degree > 180) {
			degree = 180;
		}

		Base.logger.fine("Setting servo " + index + " position to " + degree
				+ " degrees");

		// send it!
		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.TOOL_COMMAND.getCode());
		pb.add8((byte) toolhead );
		pb.add8(command);
		pb.add8((byte) 1); // length of payload.
		pb.add8((byte) degree);
		runCommand(pb.getPacket());

		// super.setServoPos(degree);

	}

	/***************************************************************************
	 * Spindle interface functions
	 **************************************************************************/
	@Deprecated
	public void setSpindleRPM(double rpm) throws RetryException {
		this.setSpindleRPM(rpm, machine.currentTool().getIndex() );
	}
	public void setSpindleRPM(double rpm, int toolhead) throws RetryException {

		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();

		// convert RPM into microseconds and then send.
		long microseconds = (int) Math.round(60 * 1000000 / rpm); // no
		// unsigned
		// ints?!?
		microseconds = Math.min(microseconds, 2 ^ 32 - 1); // limit to uint32.

		Base.logger.fine("Setting motor 2 speed to " + rpm + " RPM ("
				+ microseconds + " microseconds)");

		// send it!
		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.TOOL_COMMAND.getCode());
		pb.add8((byte) toolhead );
		pb.add8(ToolCommandCode.SET_MOTOR_2_RPM.getCode());
		pb.add8((byte) 4); // payload length
		pb.add32(microseconds);
		runCommand(pb.getPacket());

		super.setSpindleRPM(rpm, toolhead);
	}

	@Deprecated
	public void setSpindleSpeedPWM(int pwm) throws RetryException {
		this.setSpindleSpeedPWM(pwm, machine.currentTool().getIndex());
	}
		
	public void setSpindleSpeedPWM(int pwm, int toolhead) throws RetryException {

		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();

		Base.logger.fine("Setting motor 2 speed to " + pwm + " PWM");

		// send it!
		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.TOOL_COMMAND.getCode());
		pb.add8((byte) toolhead );
		pb.add8(ToolCommandCode.SET_MOTOR_2_PWM.getCode());
		pb.add8((byte) 1); // length of payload.
		pb.add8((byte) pwm);
		runCommand(pb.getPacket());

		super.setSpindleSpeedPWM(pwm, toolhead);
	}

	@Deprecated
	public void enableSpindle() throws RetryException {
		this.enableSpindle(machine.currentTool().getIndex());
		}

	public void enableSpindle(int toolhead) throws RetryException {

		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();

		// our flag variable starts with spindles enabled.
		byte flags = 1;

		// bit 1 determines direction...
		if (machine.getTool(toolhead).getSpindleDirection() == ToolModel.MOTOR_CLOCKWISE)
			flags += 2;

		Base.logger.fine("Toggling motor 2 w/ flags: "
				+ Integer.toBinaryString(flags));

		// send it!
		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.TOOL_COMMAND.getCode());
		pb.add8((byte) toolhead );
		pb.add8(ToolCommandCode.TOGGLE_MOTOR_2.getCode());
		pb.add8((byte) 1); // payload length
		pb.add8(flags);
		runCommand(pb.getPacket());

		super.enableSpindle(toolhead);
	}

	@Deprecated
	public void disableSpindle() throws RetryException {
		disableSpindle(machine.currentTool().getIndex());
	}
	
	public void disableSpindle(int toolhead) throws RetryException {

		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();

		// bit 1 determines direction...
		byte flags = 0;
		if (machine.getTool(toolhead).getSpindleDirection() == ToolModel.MOTOR_CLOCKWISE)
			flags += 2;

		Base.logger.fine("Disabling motor 2");

		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.TOOL_COMMAND.getCode());
		pb.add8((byte) toolhead);
		pb.add8(ToolCommandCode.TOGGLE_MOTOR_2.getCode());
		pb.add8((byte) 1); // payload length
		pb.add8(flags);
		runCommand(pb.getPacket());

		super.disableSpindle(toolhead);
	}

	@Deprecated
	public double getSpindleSpeedRPM() throws RetryException {
		return this.getSpindleSpeedRPM(machine.currentTool().getIndex());
	}

	public double getSpindleSpeedRPM(int toolhead) throws RetryException {

		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();

		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.TOOL_QUERY.getCode());
		pb.add8((byte) toolhead);
		pb.add8(ToolCommandCode.GET_MOTOR_2_RPM.getCode());
		PacketResponse pr = runCommand(pb.getPacket());

		// convert back to RPM
		long micros = pr.get32();
		double rpm = (60.0 * 1000000.0 / micros);

		Base.logger.fine("Current motor 2 RPM: " + rpm + " (" + micros + ")");

		// set it.
		machine.getTool(toolhead).setSpindleSpeedReadingRPM(rpm);

		return rpm;
	}

	@Deprecated
	public int getSpindleSpeedPWM() {
		return this.getSpindleSpeedPWM(machine.currentTool().getIndex());
	}
	
	public int getSpindleSpeedPWM(int toolhead) {

		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();

		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.TOOL_QUERY.getCode());
		pb.add8((byte) toolhead);
		pb.add8(ToolCommandCode.GET_MOTOR_2_PWM.getCode());
		PacketResponse pr = runQuery(pb.getPacket());

		// get it
		int pwm = pr.get8();

		Base.logger.fine("Current motor 1 PWM: " + pwm);

		// set it.
		machine.getTool(toolhead).setSpindleSpeedReadingPWM(pwm);

		return pwm;
	}

	
	/***************************************************************************
	 * Temperature interface functions
	 * @throws RetryException
	 **************************************************************************/
	@Deprecated
	public void setTemperature(double temperature) throws RetryException {
		this.setTemperature(temperature, machine.currentTool().getIndex());
	}

	public void setTemperature(double temperature, int toolhead) throws RetryException {

		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();

		// constrain our temperature.
		int temp = (int) Math.round(temperature);
		temp = Math.min(temp, 65535);

		Base.logger.fine("Setting temperature to " + temp + "C");

		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.TOOL_COMMAND.getCode());
		pb.add8((byte) toolhead);
		pb.add8(ToolCommandCode.SET_TEMP.getCode());
		pb.add8((byte) 2); // payload length
		pb.add16(temp);
		runCommand(pb.getPacket());

		super.setTemperature(temperature, toolhead);
	}

	@Deprecated
	public void readTemperature() {
		readAllTemperatures(); /// for safety, read all the temps we can
		//readTemperature(machine.currentTool().getIndex());
	}
	
	@Override
	public void readAllTemperatures() {
		Vector<ToolModel> tools = machine.getTools();

		for (ToolModel t : tools) {
			this.readTemperature(t.getIndex());
			this.getTemperatureSetting(t.getIndex());
		}
	}
	
	public void readTemperature(int toolhead) 
	{	

		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();

		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.TOOL_QUERY.getCode());
		pb.add8((byte)toolhead);
		pb.add8(ToolCommandCode.GET_TEMP.getCode());
		PacketResponse pr = runQuery(pb.getPacket());
		if (pr.getResponseCode() == PacketResponse.ResponseCode.TIMEOUT)
			Base.logger.finer("timeout reading temp");
		else if (pr.isEmpty())
			Base.logger.finer("empty response, no temp");
		else {
			int temp = pr.get16();
			machine.getTool(toolhead).setCurrentTemperature(temp);
			Base.logger.finer("New Current temperature: "
					+ machine.getTool(toolhead).getCurrentTemperature() + "C");
		}
		// Check if we should co-read platform temperatures when we read
		// head temp.
		if (machine.getTool(toolhead).alwaysReadBuildPlatformTemp()) {
			this.readPlatformTemperature(toolhead);
		}

		super.readTemperature(toolhead);
	}

	/***************************************************************************
	 * Platform Temperature interface functions
	 * 
	 * @throws RetryException
	 **************************************************************************/

	@Deprecated
	public void setPlatformTemperature(double temperature) 	throws RetryException {
		setAllPlatformTemperatures(temperature);
	}

	public void setPlatformTemperature(double temperature, int toolhead)  throws RetryException {

		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();

		int temp = (int) Math.round(temperature);
		temp = Math.min(temp, 65535);
		Base.logger.fine("Setting platform temperature to " + temp + "C");

		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.TOOL_COMMAND.getCode());
		pb.add8((byte) toolhead );
		pb.add8(ToolCommandCode.SET_PLATFORM_TEMP.getCode());
		pb.add8((byte) 2); // payload length
		pb.add16(temp);
		runCommand(pb.getPacket());
		
		machine.getTool(toolhead).setPlatformTargetTemperature(temperature);

	}

	public void setAllPlatformTemperatures(double temperature) throws RetryException {
		// constrain our temperature.
		//Set the platform temperature for any & every tool with an HBP
		for(ToolModel t : machine.getTools())
		{
			this.setPlatformTemperature(temperature, t.getIndex());
		}
	}

	@Deprecated
	public void readPlatformTemperature() {
		this.readAllPlatformTemperatures();
	}
	
	public void readPlatformTemperature(int toolhead) {
		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();

		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.TOOL_QUERY.getCode());
		pb.add8((byte) toolhead);
		pb.add8(ToolCommandCode.GET_PLATFORM_TEMP.getCode());
		
		PacketResponse pr = runQuery(pb.getPacket());
		if (pr.isEmpty()) return;
		int temp = pr.get16();
		machine.getTool(toolhead).setPlatformCurrentTemperature(temp);
		
		Base.logger.fine("Current platform temperature (T" + toolhead + "): "
						+ machine.getTool(toolhead).getPlatformCurrentTemperature() + "C");
		
	}

	public void readAllPlatformTemperatures()
	{
		
		for(ToolModel tool : machine.getTools() )
		{
			this.readPlatformTemperature(tool.getIndex());
			this.getPlatformTemperatureSetting(tool.getIndex());
		}
	
	}

	/***************************************************************************
	 * Flood Coolant interface functions
	 **************************************************************************/
	public void enableFloodCoolant() {
		// TODO: throw unsupported exception

		super.enableFloodCoolant();
	}

	public void disableFloodCoolant() {
		// TODO: throw unsupported exception

		super.disableFloodCoolant();
	}

	/***************************************************************************
	 * Mist Coolant interface functions
	 **************************************************************************/
	public void enableMistCoolant() {
		// TODO: throw unsupported exception

		super.enableMistCoolant();
	}

	public void disableMistCoolant() {
		// TODO: throw unsupported exception

		super.disableMistCoolant();
	}

	/***************************************************************************
	 * Fan interface functions
	 * 
	 * @throws RetryException
	 **************************************************************************/
	@Deprecated
	public void enableFan() throws RetryException {
		this.enableFan(machine.currentTool().getIndex() );
	}
	
	public void enableFan(int toolhead) throws RetryException {
		
		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();

		Base.logger.fine("Enabling fan");

		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.TOOL_COMMAND.getCode());
		pb.add8((byte) toolhead);
		Base.logger.fine("Tool index " +toolhead );
		pb.add8(ToolCommandCode.TOGGLE_FAN.getCode());
		pb.add8((byte) 1); // payload length
		pb.add8((byte) 1); // enable
		runCommand(pb.getPacket());
		super.enableFan(toolhead);
	}

	@Deprecated
	public void disableFan() throws RetryException {
		this.disableFan(machine.currentTool().getIndex());
	}
	public void disableFan(int toolhead) throws RetryException {
		
		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();

		Base.logger.fine("Disabling fan");

		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.TOOL_COMMAND.getCode());
		pb.add8((byte) toolhead );
		pb.add8(ToolCommandCode.TOGGLE_FAN.getCode());
		pb.add8((byte) 1); // payload length
		pb.add8((byte) 0); // disable
		runCommand(pb.getPacket());

		super.disableFan(toolhead);
	}

	@Deprecated
	public void setAutomatedBuildPlatformRunning(boolean state)
		throws RetryException {
		this.setAutomatedBuildPlatformRunning(state, machine.currentTool().getIndex());
	}
	
	public void setAutomatedBuildPlatformRunning(boolean state, int toolhead)
			throws RetryException {
		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();

		Base.logger.fine("Toggling ABP to " + state);
		byte newState = state ? (byte) 1 : (byte) 0;

		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.TOOL_COMMAND.getCode());
		pb.add8((byte) toolhead );
		pb.add8(ToolCommandCode.TOGGLE_ABP.getCode());
		pb.add8((byte) 1); // payload length
		pb.add8((byte) newState); // enable(1)disable(0)
		runCommand(pb.getPacket());

		super.setAutomatedBuildPlatformRunning(state, toolhead);

	}

	/***************************************************************************
	 * Valve interface functions
	 * 
	 * @throws RetryException
	 **************************************************************************/
	@Deprecated
	public void openValve() throws RetryException {
		openValve(machine.currentTool().getIndex());
	}
	
	public void openValve(int toolhead) throws RetryException {
		Base.logger.fine("Opening valve");
		
		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();


		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.TOOL_COMMAND.getCode());
		pb.add8((byte) toolhead );
		pb.add8(ToolCommandCode.TOGGLE_VALVE.getCode());
		pb.add8((byte) 1); // payload length
		pb.add8((byte) 1); // enable
		runCommand(pb.getPacket());

		super.openValve(toolhead);
	}


	@Deprecated
	public void closeValve() throws RetryException {
		closeValve(machine.currentTool().getIndex());
	}

	
	public void closeValve(int toolhead) throws RetryException {
		Base.logger.fine("Closing valve");

		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();

		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.TOOL_COMMAND.getCode());
		pb.add8((byte) toolhead);
		pb.add8(ToolCommandCode.TOGGLE_VALVE.getCode());
		pb.add8((byte) 1); // payload length
		pb.add8((byte) 0); // disable
		runCommand(pb.getPacket());

		super.closeValve(toolhead);
	}

	/***************************************************************************
	 * Collet interface functions
	 **************************************************************************/
	public void openCollet() {
		// TODO: throw unsupported exception.

		super.openCollet();
	}

	public void closeCollet() {
		// TODO: throw unsupported exception.

		super.closeCollet();
	}

	/***************************************************************************
	 * Pause/unpause functionality for asynchronous devices
	 **************************************************************************/
	public void pause() {
		Base.logger.fine("Sending asynch pause command");
		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.PAUSE.getCode());
		runQuery(pb.getPacket());
	}

	public void unpause() {
		Base.logger.fine("Sending asynch unpause command");
		// There is no explicit unpause command on the Sanguino3G; instead we
		// use the pause command to toggle the pause state.
		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.PAUSE.getCode());
		runQuery(pb.getPacket());
	}

	/***************************************************************************
	 * Various timer and math functions.
	 **************************************************************************/

	private Point5d getAbsDeltaDistance(Point5d current, Point5d target) {
		// calculate our deltas.
		Point5d delta = new Point5d();
		delta.sub(target, current); // delta = target - current
		delta.absolute();

		return delta;
	}

	protected Point5d getAbsDeltaSteps(Point5d current, Point5d target) {
		return machine.mmToSteps(getAbsDeltaDistance(current, target));
	}

	/**
	 * 
	 * @param current
	 * @param target
	 * @param feedrate
	 *            Feedrate in mm per minute
	 * @return
	 */
	protected long convertFeedrateToMicros(Point5d current, Point5d target,
			double feedrate) {
		Point5d deltaDistance = getAbsDeltaDistance(current, target);
		Point5d deltaSteps = machine.mmToSteps(deltaDistance);
		double masterSteps = getLongestLength(deltaSteps);
		// how long is our line length?
		// We calculate this manually, so that we only account for active axes.
		// double distanceSq = 0.0;
		// for (AxisId axis : machine.getAvailableAxes()) {
		// distanceSq += deltaDistance.axis(axis);
		// }
		// double distance = Math.sqrt(distanceSq);
		double distance = deltaDistance.magnitude();
		// distance is in mm
		// feedrate is in mm/min
		// distance / feedrate * 60,000,000 = move duration in microseconds
		double micros = distance / feedrate * 60000000.0;
		// micros / masterSteps = time between steps for master axis.
		double step_delay = micros / masterSteps;
		return (long) Math.round(step_delay);
	}

	protected double getLongestLength(Point5d p) {
		// find the dominant axis.
		double longest = 0d;
		for (int i = 0; i < 5; i++) {
			longest = Math.max(longest, p.get(i));
		}
		return longest;
	}

	public String getDriverName() {
		return "Sanguino3G";
	}

	/***************************************************************************
	 * Stop and system state reset
	 **************************************************************************/
	final private Version extendedStopVersion = new Version(2, 7);

	/// resets the bot, doesn't stop threads
	public void stop(boolean abort) {
		PacketBuilder pb;
		if (!abort && version.atLeast(extendedStopVersion)) {
			Base.logger.fine("Stop motion.");
			pb = new PacketBuilder(
					MotherboardCommandCode.EXTENDED_STOP.getCode());
			// Clear command queue and stop motion
			pb.add8(1 << 0 | 1 << 1);

		} else {
			Base.logger.fine("Stop all.");
			pb = new PacketBuilder(MotherboardCommandCode.ABORT.getCode());
		}
		Thread.interrupted(); // Clear interrupted status
		runQuery(pb.getPacket());
		// invalidate position, force reconciliation.
		invalidatePosition();
	}

	protected Point5d reconcilePosition() throws RetryException {
		// If we're writing to a file, we can't actually know what the current
		// position is.
		if (fileCaptureOstream != null) {
			return null;
		}
		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.GET_POSITION.getCode());
		PacketResponse pr = runCommand(pb.getPacket());
		Point5d steps = new Point5d(pr.get32(), pr.get32(), pr.get32(), 0, 0);
		// Useful quickie debugs
		// System.err.println("Reconciling : "+machine.stepsToMM(steps).toString());
		return machine.stepsToMM(steps);
	}

	
	public void reset() {
		Base.logger.info("Reset Board");
		if (isInitialized() && version.compareTo(new Version(1, 4)) >= 0) {
			// WDT reset introduced in version 1.4 firmware
			PacketBuilder pb = new PacketBuilder(
					MotherboardCommandCode.RESET.getCode());
			Thread.interrupted(); // Clear interrupted status
			runQuery(pb.getPacket());
			// invalidate position, force reconciliation.
			invalidatePosition();
		}
		setInitialized(false);
		initialize();
	}

	/**
	 * Check out EEPROM settings for correctness
	 */
	private void checkEEPROM() {
		if (!eepromChecked) {
			// Versions 2 and up have onboard eeprom defaults and rely on 0xff
			// values
			eepromChecked = true;
			if (version.getMajor() < 2) {
				byte versionBytes[] = readFromEEPROM(
						Sanguino3GEEPRPOM.EEPROM_CHECK_OFFSET, 2);
				if (versionBytes == null || versionBytes.length < 2)
					return;
				if ((versionBytes[0] != Sanguino3GEEPRPOM.EEPROM_CHECK_LOW)
						|| (versionBytes[1] != Sanguino3GEEPRPOM.EEPROM_CHECK_HIGH)) {
					Base.logger.severe("Cleaning EEPROM to v1.X state");
					// Wipe EEPROM
					byte eepromWipe[] = new byte[16];
					Arrays.fill(eepromWipe, (byte) 0x00);
					eepromWipe[0] = Sanguino3GEEPRPOM.EEPROM_CHECK_LOW;
					eepromWipe[1] = Sanguino3GEEPRPOM.EEPROM_CHECK_HIGH;
					writeToEEPROM(0, eepromWipe);
					Arrays.fill(eepromWipe, (byte) 0x00);
					for (int i = 16; i < 256; i += 16) {
						writeToEEPROM(i, eepromWipe);
					}
				}
			}
		}
	}

	protected void writeToEEPROM(int offset, byte[] data) {
		assert data.length <= 16;
		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.WRITE_EEPROM.getCode());
		pb.add16(offset);
		pb.add8(data.length);
		for (byte b : data) {
			pb.add8(b);
		}
		PacketResponse pr = runQuery(pb.getPacket());
		assert pr.get8() == data.length;
	}

	@Deprecated
	protected byte[] readFromToolEEPROM(int offset, int len)
	{
		return readFromToolEEPROM(offset, len, machine.currentTool().getIndex());
	}

	protected byte[] readFromToolEEPROM(int offset, int len, int toolhead) {
		
		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();

		
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.TOOL_QUERY.getCode());
		pb.add8((byte) toolhead);
		pb.add8(ToolCommandCode.READ_FROM_EEPROM.getCode());
		pb.add16(offset);
		pb.add8(len);
		PacketResponse pr = runQuery(pb.getPacket());
		
		if (pr.isOK()) {
			int rvlen = Math.min(pr.getPayload().length - 1, len);
			byte[] rv = new byte[rvlen];
			// Copy removes the first response byte from the packet payload.
			System.arraycopy(pr.getPayload(), 1, rv, 0, rvlen);
			return rv;
		} else {
			Base.logger.severe("On tool read: "
					+ pr.getResponseCode().getMessage());
		}
		Base.logger.severe("readFromToolEEPROM null" + offset +" " + len + " " + toolhead);
		return null;
	}

	/**
	 * 
	 * @param offset
	 * @param data
	 */
	@Deprecated
	protected void writeToToolEEPROM(int offset, byte[] data) {
		writeToToolEEPROM(offset, data, machine.currentTool().getIndex());
	}

	/**
	 * 
	 * @param offset
	 * @param data
	 * @param toolhead
	 */
	protected void writeToToolEEPROM(int offset, byte[] data, int toolhead) {
		
		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();

		final int MAX_PAYLOAD = 11;
		while (data.length > MAX_PAYLOAD) {
			byte[] head = new byte[MAX_PAYLOAD];
			byte[] tail = new byte[data.length - MAX_PAYLOAD];
			System.arraycopy(data, 0, head, 0, MAX_PAYLOAD);
			System.arraycopy(data, MAX_PAYLOAD, tail, 0, data.length
					- MAX_PAYLOAD);
			writeToToolEEPROM(offset, head, toolhead);
			offset += MAX_PAYLOAD;
			data = tail;
		}
		PacketBuilder slavepb = new PacketBuilder(
				MotherboardCommandCode.TOOL_QUERY.getCode());
		slavepb.add8((byte) toolhead);
		slavepb.add8(ToolCommandCode.WRITE_TO_EEPROM.getCode());
		slavepb.add16(offset);
		slavepb.add8(data.length);
		for (byte b : data) {
			slavepb.add8(b);
		}
		PacketResponse slavepr = runQuery(slavepb.getPacket());
		slavepr.printDebug();
		// If the tool index is 127/255, we should not expect a response (it's a
		// broadcast packet).
		assert (toolhead == 255) || (toolhead == 127)
				|| (slavepr.get8() == data.length);
	}

	/**
	 * Sends a request to the MoBo to read an eeprom address
	 * @param offset Offset from the bottom of EEPROM memory
	 * @param len	number of bytes to read from EEPROM
	 * @return a byte array of size length on success, a null object on failure
	 */
	protected byte[] readFromEEPROM(int offset, int len) {
		final int MAX_EEPROM_READ_SZ = 16;
		if(len > MAX_EEPROM_READ_SZ)
			Base.logger.severe("readFromEEPROM too big for: " + offset + " size: " + len);

		assert len <= 16;

		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.READ_EEPROM.getCode());
		pb.add16(offset);
		pb.add8(len);

		
		PacketResponse pr = runQuery(pb.getPacket());
		if (pr.isOK()) {
			Base.logger.finest("readFromEEPROM ok for: " + offset + " size: " + len);
			int rvlen = Math.min(pr.getPayload().length - 1, len);
			byte[] rv = new byte[rvlen];
			// Copy removes the first response byte from the packet payload.
			System.arraycopy(pr.getPayload(), 1, rv, 0, rvlen);
			return rv;
		}
		Base.logger.severe("readFromEEPROM fail for: " + offset + " size: " + len);
		Base.logger.severe("readFromEEPROM PR is: " + pr.toString());
		return null;
	}

	public EnumSet<AxisId> getInvertedAxes() {
		checkEEPROM();
		byte[] b = readFromEEPROM(
				Sanguino3GEEPRPOM.EEPROM_AXIS_INVERSION_OFFSET, 1);
		EnumSet<AxisId> r = EnumSet.noneOf(AxisId.class);
		if ((b[0] & (0x01 << 0)) != 0)
			r.add(AxisId.X);
		if ((b[0] & (0x01 << 1)) != 0)
			r.add(AxisId.Y);
		if ((b[0] & (0x01 << 2)) != 0)
			r.add(AxisId.Z);
		if ((b[0] & (0x01 << 3)) != 0)
			r.add(AxisId.A);
		if ((b[0] & (0x01 << 4)) != 0)
			r.add(AxisId.B);
		if ((b[0] & (0x01 << 7)) != 0)
			r.add(AxisId.V);
		return r;
	}

	public void setInvertedAxes(EnumSet<AxisId> axes) {
		byte b[] = new byte[1];
		if (axes.contains(AxisId.X))
			b[0] = (byte) (b[0] | (0x01 << 0));
		if (axes.contains(AxisId.Y))
			b[0] = (byte) (b[0] | (0x01 << 1));
		if (axes.contains(AxisId.Z))
			b[0] = (byte) (b[0] | (0x01 << 2));
		if (axes.contains(AxisId.A))
			b[0] = (byte) (b[0] | (0x01 << 3));
		if (axes.contains(AxisId.B))
			b[0] = (byte) (b[0] | (0x01 << 4));
		if (axes.contains(AxisId.V))
			b[0] = (byte) (b[0] | (0x01 << 7));
		writeToEEPROM(Sanguino3GEEPRPOM.EEPROM_AXIS_INVERSION_OFFSET, b);
	}

	public String getMachineName() {
		checkEEPROM();
		byte[] data = readFromEEPROM(
				Sanguino3GEEPRPOM.EEPROM_MACHINE_NAME_OFFSET,
				Sanguino3GEEPRPOM.MAX_MACHINE_NAME_LEN);
		if (data == null) {
			return new String();
		}
		try {
			int len = 0;
			while (len < Sanguino3GEEPRPOM.MAX_MACHINE_NAME_LEN
					&& data[len] != 0)
				len++;
			return new String(data, 0, len, "ISO-8859-1");
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
			return null;
		}
	}

	public void setMachineName(String machineName) {
		machineName = new String(machineName);
		if (machineName.length() > 16) {
			machineName = machineName.substring(0, 16);
		}
		byte b[] = new byte[16];
		int idx = 0;
		for (byte sb : machineName.getBytes()) {
			b[idx++] = sb;
			if (idx == 16)
				break;
		}
		if (idx < 16)
			b[idx] = 0;
		writeToEEPROM(Sanguino3GEEPRPOM.EEPROM_MACHINE_NAME_OFFSET, b);
	}

	/**
	 * Reads a 32 bit double from EEPROM on the MightyBoard to indicate the 
	 * number of steps of offset from end-stop to home on the specified axis
	 */
	public double getAxisHomeOffset(int axis) {
		if ((axis < 0) || (axis > 4)) {
			// TODO: handle this
			return 0;
		}

		checkEEPROM();
		byte[] r = readFromEEPROM(
				Sanguino3GEEPRPOM.EEPROM_AXIS_HOME_POSITIONS_OFFSET + (axis * 4), 4);

		double val = 0;
		for (int i = 0; i < 4; i++) {
			val = val + (((int) r[i] & 0xff) << 8 * i);
		}

		Point5d stepsPerMM = getMachine().getStepsPerMM();
		switch (axis) {
		case 0:
			val = val / stepsPerMM.x();
			break;
		case 1:
			val = val / stepsPerMM.y();
			break;
		case 2:
			val = val / stepsPerMM.z();
			break;
		case 3:
			val = val / stepsPerMM.a();
			break;
		case 4:
			val = val / stepsPerMM.b();
			break;
		}

		return val;
	}
        

	public void setAxisHomeOffset(int axis, double offset) {
		if ((axis < 0) || (axis > 4)) {
			// TODO: handle this
			return;
		}

		int offsetSteps = 0;

		Point5d stepsPerMM = getMachine().getStepsPerMM();
		switch (axis) {
		case 0:
			offsetSteps = (int) (offset * stepsPerMM.x());
			break;
		case 1:
			offsetSteps = (int) (offset * stepsPerMM.y());
			break;
		case 2:
			offsetSteps = (int) (offset * stepsPerMM.z());
			break;
		case 3:
			offsetSteps = (int) (offset * stepsPerMM.a());
			break;
		case 4:
			offsetSteps = (int) (offset * stepsPerMM.b());
			break;
		}

		writeToEEPROM(Sanguino3GEEPRPOM.EEPROM_AXIS_HOME_POSITIONS_OFFSET
				+ axis * 4, intToLE(offsetSteps));
	}
    
	@Override
	public boolean hasToolheadsOffset() { return false;}
	
	@Override
        public double getToolheadsOffset(int axis) {
            Base.logger.info("Cannot get tolerance error for S3G driver");
            return 0.0;
        }

	@Override
        public void eepromStoreToolDelta(int axis, double offset){
            Base.logger.info("Cannot store tolerance error for S3G driver");
            return;
        }
        
        @Override
        public int getAccelerationRate(){
            Base.logger.info("Cannot get acceleration rate for S3G driver");
            return 0;
        }
        
        @Override
        public void setAccelerationRate(int rate){
            Base.logger.info("Cannot set acceleration rate for S3G driver");

        }
        
        @Override
        public byte getAccelerationStatus(){
            Base.logger.info("Cannot get acceleration status for S3G driver");
            return (byte)0;
        }
        
        @Override
        public void setAccelerationStatus(byte status){
            Base.logger.info("Cannot set acceleration status for S3G driver");
        }
        
        @Override
        public int getAxisAccelerationRate(int axis){
            Base.logger.info("Cannot get acceleration axis rate for S3G driver");
            return 0;
        }
        
        @Override
        public void setAxisAccelerationRate(int axis, int rate){
            Base.logger.info("Cannot set acceleration axis rate for S3G driver");
        }
        
        @Override
        public double getAxisJerk(int axis){
            Base.logger.info("Cannot get acceleration axis jerk for S3G driver");
            return 0.0;
        }
        
        @Override
        public void setAxisJerk(int axis, double jerk){
            Base.logger.info("Cannot set acceleration axis rate for S3G driver");
        }
        
        @Override
        public int getAccelerationMinimumSpeed(){
            Base.logger.info("Cannot get acceleration minimum speed for S3G driver");
            return 0;
        }
        
        @Override
        public void setAccelerationMinimumSpeed(int speed){
            Base.logger.info("Cannot set acceleration minimum speed for S3G driver");
        }
        
        @Override
	public boolean hasAcceleration() { return false;}
 
        @Override
	public boolean hasJettyAcceleration() { return false;}

        @Override
	public boolean hasAdvancedFeatures() { return false;}
 
	@Override
	public int getEEPROMParamInt(EEPROMParams param) {
            Base.logger.info("This EEPROM interface, getEEPROMParamInt, is not supported for this driver");
            return 0;
        }

	@Override
	public long getEEPROMParamUInt(EEPROMParams param){
            Base.logger.info("This EEPROM interface, getEEPROMParamUInt, is not supported for this driver");
            return 0l;
        }

	@Override
	public double getEEPROMParamFloat(EEPROMParams param) {
            Base.logger.info("This EEPROM interface, getEEPROMParamDouble, is not supported for this driver");
            return 0f;
        }

	@Override
	public void setEEPROMParam(EEPROMParams param, int value) {
            Base.logger.info("This EEPROM interface, setEEPROMParam(param,int), is not supported for this driver");
        }

	@Override
	public void setEEPROMParam(EEPROMParams param, long value) {
            Base.logger.info("This EEPROM interface, setEEPROMParam(param,long), is not supported for this driver");
        }

	@Override
	public void setEEPROMParam(EEPROMParams param, double value) {
            Base.logger.info("This EEPROM interface, setEEPROMParam(param,double), is not supported for this driver");
        }

	public void storeHomePositions(EnumSet<AxisId> axes) throws RetryException {
		byte b = 0;
		if (axes.contains(AxisId.X))
			b = (byte) (b | (0x01 << 0));
		if (axes.contains(AxisId.Y))
			b = (byte) (b | (0x01 << 1));
		if (axes.contains(AxisId.Z))
			b = (byte) (b | (0x01 << 2));
		if (axes.contains(AxisId.A))
			b = (byte) (b | (0x01 << 3));
		if (axes.contains(AxisId.B))
			b = (byte) (b | (0x01 << 4));

		Base.logger.fine("Storing home positions ["
				+ ((axes.contains(AxisId.X)) ? "X" : "")
				+ ((axes.contains(AxisId.Y)) ? "Y" : "")
				+ ((axes.contains(AxisId.Z)) ? "Z" : "")
				+ ((axes.contains(AxisId.A)) ? "A" : "")
				+ ((axes.contains(AxisId.B)) ? "B" : "") + "]");

		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.STORE_HOME_POSITIONS.getCode());
		pb.add8(b);

		runCommand(pb.getPacket());
	}

	public void recallHomePositions(EnumSet<AxisId> axes) throws RetryException {
		byte b = 0;
		if (axes.contains(AxisId.X))
			b = (byte) (b | (0x01 << 0));
		if (axes.contains(AxisId.Y))
			b = (byte) (b | (0x01 << 1));
		if (axes.contains(AxisId.Z))
			b = (byte) (b | (0x01 << 2));
		if (axes.contains(AxisId.A))
			b = (byte) (b | (0x01 << 3));
		if (axes.contains(AxisId.B))
			b = (byte) (b | (0x01 << 4));

		Base.logger.fine("Recalling home positions ["
				+ ((axes.contains(AxisId.X)) ? "X" : "")
				+ ((axes.contains(AxisId.Y)) ? "Y" : "")
				+ ((axes.contains(AxisId.Z)) ? "Z" : "")
				+ ((axes.contains(AxisId.A)) ? "A" : "")
				+ ((axes.contains(AxisId.B)) ? "B" : "") + "]");

		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.RECALL_HOME_POSITIONS.getCode());
		pb.add8(b);

		runCommand(pb.getPacket());

		invalidatePosition();
	}

	public boolean hasFeatureOnboardParameters() {
		if (!isInitialized())
			return false;
		return version.compareTo(new Version(1, 2)) >= 0;
	}

	public void createThermistorTable(int which, double r0, double t0, double beta, int toolhead) {

		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();

		// Generate a thermistor table for r0 = 100K.
		final int ADC_RANGE = 1024;
		final int NUMTEMPS = 20;
		byte table[] = new byte[NUMTEMPS * 2 * 2];
		class ThermistorConverter {
			final double ZERO_C_IN_KELVIN = 273.15;
			public double vadc, rs, vs, k, beta;

			public ThermistorConverter(double r0, double t0C, double beta,
					double r2) {
				this.beta = beta;
				this.vs = this.vadc = 5.0;
				final double t0K = ZERO_C_IN_KELVIN + t0C;
				this.k = r0 * Math.exp(-beta / t0K);
				this.rs = r2;
			}

			public double temp(double adc) {
				// Convert ADC reading into a temperature in Celsius
				double v = adc * this.vadc / ADC_RANGE;
				double r = this.rs * v / (this.vs - v);
				return (this.beta / Math.log(r / this.k)) - ZERO_C_IN_KELVIN;
			}
		}
		;
		ThermistorConverter tc = new ThermistorConverter(r0, t0, beta, 4700.0);
		double adc = 1; // matching the python script's choices for now;
		// we could do better with this distribution.
		for (int i = 0; i < NUMTEMPS; i++) {
			double temp = tc.temp(adc);
			// extruder controller is little-endian
			int tempi = (int) temp;
			int adci = (int) adc;
			Base.logger.fine("{ " + Integer.toString(adci) + ","
					+ Integer.toString(tempi) + " }");
			table[(2 * 2 * i) + 0] = (byte) (adci & 0xff); // ADC low
			table[(2 * 2 * i) + 1] = (byte) (adci >> 8); // ADC high
			table[(2 * 2 * i) + 2] = (byte) (tempi & 0xff); // temp low
			table[(2 * 2 * i) + 3] = (byte) (tempi >> 8); // temp high
			adc += (ADC_RANGE / (NUMTEMPS - 1));
		}
		// Add indicators
		byte eepromIndicator[] = new byte[2];

		eepromIndicator[0] = Sanguino3GEEPRPOM.EEPROM_CHECK_LOW;
		eepromIndicator[1] = Sanguino3GEEPRPOM.EEPROM_CHECK_HIGH;
		writeToToolEEPROM(0, eepromIndicator,toolhead);

		writeToToolEEPROM(Sanguino3GEEPRPOM.ECThermistorOffsets.beta(which), intToLE((int) beta), toolhead);
		writeToToolEEPROM(Sanguino3GEEPRPOM.ECThermistorOffsets.r0(which), intToLE((int) r0), toolhead);
		writeToToolEEPROM(Sanguino3GEEPRPOM.ECThermistorOffsets.t0(which), intToLE((int) t0), toolhead);
		writeToToolEEPROM(Sanguino3GEEPRPOM.ECThermistorOffsets.data(which), table, toolhead);
		}

	public boolean getCoolingFanEnabled(int toolhead) {
		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();
		
		byte[] a = readFromToolEEPROM(CoolingFanOffsets.COOLING_FAN_ENABLE, 1, toolhead);

		return (a[0] == 1);
	}

	public int getCoolingFanSetpoint(int toolhead) {
		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();
		return read16FromToolEEPROM(CoolingFanOffsets.COOLING_FAN_SETPOINT_C, 50, toolhead);
	}

	public void setCoolingFanParameters(boolean enabled, int setpoint, int toolhead) {
		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();
		if (enabled) {
			writeToToolEEPROM(CoolingFanOffsets.COOLING_FAN_ENABLE, new byte[] { 0x1 }, toolhead);
		} else {
			writeToToolEEPROM(CoolingFanOffsets.COOLING_FAN_ENABLE, new byte[] { 0x0 }, toolhead);
		}
		writeToToolEEPROM(CoolingFanOffsets.COOLING_FAN_SETPOINT_C, intToLE(setpoint), toolhead);
	}

	// / Convert an int to 32bit int
	protected byte[] intToLE(int s, int sz) {
		byte buf[] = new byte[sz];
		for (int i = 0; i < sz; i++) {
			buf[i] = (byte) (s & 0xff);
			s = s >>> 8;
		}
		return buf;
	}

	protected byte[] floatToLE(float f) {
		byte buf[] = new byte[2];
		double d = f;
		double intPart = Math.floor(d);
		double fracPart = Math.floor((d - intPart) * 256.0);
		buf[0] = (byte) intPart;
		buf[1] = (byte) fracPart;
		return buf;
	}

	protected byte[] intToLE(int s) {
		return intToLE(s, 4);
	}
        
        protected float byte16LEToFloat(byte[] r, int offset) {
		return (float)(byteToInt(r[offset+1]) | byteToInt(r[offset])<<8)/255.0f;
	}
	protected float byte16LEToFloat(byte[] r) {
		return byte16LEToFloat(r, 0);
	}


	ResponseCode convertSDCode(int code) {
		switch (code) {
		case 0:
			return ResponseCode.SUCCESS;
		case 1:
			return ResponseCode.FAIL_NO_CARD;
		case 2:
			return ResponseCode.FAIL_INIT;
		case 3:
			return ResponseCode.FAIL_PARTITION;
		case 4:
			return ResponseCode.FAIL_FS;
		case 5:
			return ResponseCode.FAIL_ROOT_DIR;
		case 6:
			return ResponseCode.FAIL_LOCKED;
		case 7:
			return ResponseCode.FAIL_NO_FILE;
		default:
		}
		return ResponseCode.FAIL_GENERIC;
	}

	FileOutputStream fileCaptureOstream = null;

	public void beginFileCapture(String path) throws FileNotFoundException {
		fileCaptureOstream = new FileOutputStream(new File(path));
	}

	public void endFileCapture() throws IOException {
		fileCaptureOstream.close();
		fileCaptureOstream = null;
	}

	public ResponseCode beginCapture(String filename) {
		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.CAPTURE_TO_FILE.getCode());
		for (byte b : filename.getBytes()) {
			pb.add8(b);
		}
		pb.add8(0); // null-terminate string
		PacketResponse pr = runQuery(pb.getPacket());
		return convertSDCode(pr.get8());
	}

	public int endCapture() {
		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.END_CAPTURE.getCode());
		PacketResponse pr = runQuery(pb.getPacket());
		return pr.get32();
	}

	public ResponseCode playback(String filename) {
		PacketBuilder pb = new PacketBuilder(
				MotherboardCommandCode.PLAYBACK_CAPTURE.getCode());
		for (byte b : filename.getBytes()) {
			pb.add8(b);
		}
		pb.add8(0); // null-terminate string
		PacketResponse pr = runQuery(pb.getPacket());
		return convertSDCode(pr.get8());
	}

	public boolean hasFeatureSDCardCapture() {
		if (!isInitialized())
			return false;
		return version.compareTo(new Version(1, 3)) >= 0;
	}

	public List<String> getFileList() {
		Vector<String> fileList = new Vector<String>();
		boolean reset = true;
		while (true) {
			PacketBuilder pb = new PacketBuilder(
					MotherboardCommandCode.NEXT_FILENAME.getCode());
			pb.add8(reset ? 1 : 0);
			reset = false;
			PacketResponse pr = runQuery(pb.getPacket());
			ResponseCode rc = convertSDCode(pr.get8());
			if (rc != ResponseCode.SUCCESS) {
				return fileList;
			}
			StringBuffer s = new StringBuffer();
			while (true) {
				char c = (char) pr.get8();
				if (c == 0)
					break;
				s.append(c);
			}
			if (s.length() == 0)
				break;
			fileList.add(s.toString());
		}
		return fileList;
	}

	public int getBeta(int which, int toolhead) {
		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();

		byte r[] = readFromToolEEPROM(Sanguino3GEEPRPOM.ECThermistorOffsets.beta(which),4,toolhead);
		int val = 0;
		if( r == null || r.length < 4 ) {
			Base.logger.fine("failure to read getBeta");
			return val;
		}
		for (int i = 0; i < 4; i++) {
			val = val + (((int) r[i] & 0xff) << 8 * i);
		}
		return val;
	}

	public int getR0(int which, int toolhead) {
		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();
		byte r[] = readFromToolEEPROM(Sanguino3GEEPRPOM.ECThermistorOffsets.r0(which),4,toolhead);
		int val = 0;
		if( r == null || r.length < 4 ) {
			Base.logger.fine("failure to read getR0");
			return val;
		}
		for (int i = 0; i < 4; i++) {
			val = val + (((int) r[i] & 0xff) << 8 * i);
		}
		return val;
	}

	public int getT0(int which, int toolhead) {
		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();
		byte r[] = readFromToolEEPROM(Sanguino3GEEPRPOM.ECThermistorOffsets.t0(which),4,toolhead);
		int val = 0;
		if( r == null || r.length < 4 ) {
			Base.logger.fine("failure to read getT0");
			return val;
		}
		for (int i = 0; i < 4; i++) {
			val = val + (((int) r[i] & 0xff) << 8 * i);
		}
		return val;
	}

	/// Offsets from start of a Toolhead EEPROM section 
	// to the specified data 
	final private static class ECBackoffOffsets {
		// / Backoff stop time, in ms: 2 bytes
		final static int STOP_MS = 0x0004;
		// / Backoff reverse time, in ms: 2 bytes
		final static int REVERSE_MS = 0x0006;
		// / Backoff forward time, in ms: 2 bytes
		final static int FORWARD_MS = 0x0008;
		// / Backoff trigger time, in ms: 2 bytes
		final static int TRIGGER_MS = 0x000A;
	};

	final private static class PIDOffsets {
		final static int PID_EXTRUDER = 0x000C;
		final static int PID_HBP = 0x0012;
		final static int P_TERM_OFFSET = 0x0000;
		final static int I_TERM_OFFSET = 0x0002;
		final static int D_TERM_OFFSET = 0x0004;
	};

	final private static class CoolingFanOffsets {
		final static int COOLING_FAN_ENABLE = 0x001c;
		final static int COOLING_FAN_SETPOINT_C = 0x001d;
	};


	@Deprecated
	protected int read16FromToolEEPROM(int offset, int defaultValue) {
		return read16FromToolEEPROM(offset, defaultValue, machine.currentTool().getIndex());
	}
	
	protected int read16FromToolEEPROM(int offset, int defaultValue, int toolhead) {
		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();
		byte r[] = readFromToolEEPROM(offset, 2, toolhead);
		int val = ((int) r[0]) & 0xff;
		val += (((int) r[1]) & 0xff) << 8;
		if (val == 0x0ffff)
			return defaultValue;
		return val;
	}

	private int byteToInt(byte b) {
		return ((int) b) & 0xff;
	}

	private float readFloat16FromToolEEPROM(int offset, float defaultValue, int toolhead) {
		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();
		byte r[] = readFromToolEEPROM(offset, 2, toolhead);
		if (r == null) {
			Base.logger.severe("null read from tool at " + offset + " for tool " + toolhead + " default " +defaultValue);
			return defaultValue;
		}
		if (r[0] == (byte) 0xff && r[1] == (byte) 0xff)
			return defaultValue;
		return (float) byteToInt(r[0]) + ((float) byteToInt(r[1])) / 256.0f;
	}

	public BackoffParameters getBackoffParameters(int toolhead) {
		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();

		BackoffParameters bp = new BackoffParameters();
		bp.forwardMs = read16FromToolEEPROM(ECBackoffOffsets.FORWARD_MS, 300, toolhead);
		bp.stopMs = read16FromToolEEPROM(ECBackoffOffsets.STOP_MS, 5, toolhead);
		bp.reverseMs = read16FromToolEEPROM(ECBackoffOffsets.REVERSE_MS, 500, toolhead);
		bp.triggerMs = read16FromToolEEPROM(ECBackoffOffsets.TRIGGER_MS, 300, toolhead);
		return bp;
	}
	
	public void setBackoffParameters(BackoffParameters bp, int toolhead) {
		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();

		writeToToolEEPROM(ECBackoffOffsets.FORWARD_MS,intToLE(bp.forwardMs,2), toolhead);
		writeToToolEEPROM(ECBackoffOffsets.STOP_MS,intToLE(bp.stopMs,2), toolhead);
		writeToToolEEPROM(ECBackoffOffsets.REVERSE_MS,intToLE(bp.reverseMs,2), toolhead);
		writeToToolEEPROM(ECBackoffOffsets.TRIGGER_MS,intToLE(bp.triggerMs,2), toolhead);
	}

	public PIDParameters getPIDParameters(int which, int toolhead) {
		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();
		PIDParameters pp = new PIDParameters();
		int offset = (which == OnboardParameters.EXTRUDER)?PIDOffsets.PID_EXTRUDER:PIDOffsets.PID_HBP;
		pp.p = readFloat16FromToolEEPROM(offset+PIDOffsets.P_TERM_OFFSET, 7.0f, toolhead);
		pp.i = readFloat16FromToolEEPROM(offset+PIDOffsets.I_TERM_OFFSET, 0.325f, toolhead);
		pp.d = readFloat16FromToolEEPROM(offset+PIDOffsets.D_TERM_OFFSET, 36.0f, toolhead);
		return pp;
	}
	
	public void setPIDParameters(int which, PIDParameters pp, int toolhead) {
		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();
		int offset = (which == OnboardParameters.EXTRUDER)?PIDOffsets.PID_EXTRUDER:PIDOffsets.PID_HBP;
		writeToToolEEPROM(offset+PIDOffsets.P_TERM_OFFSET,floatToLE(pp.p),toolhead);
		writeToToolEEPROM(offset+PIDOffsets.I_TERM_OFFSET,floatToLE(pp.i),toolhead);
		writeToToolEEPROM(offset+PIDOffsets.D_TERM_OFFSET,floatToLE(pp.d),toolhead);
	}

	/**
	 * Reset to the factory state. This ordinarily means writing 0xff over the
	 * entire eeprom.
	 * @throws RetryException 
	 */
	@Override
	public void resetSettingsToFactory() throws RetryException {
		Base.logger.finer("resetting to Factory in Sanguino3G");
		resetSettingsToBlank();
	}

	@Override
	public void resetSettingsToBlank() throws RetryException  {
		Base.logger.finer("resetting to Blank in Sanguino3G");
		byte eepromWipe[] = new byte[16];
		Arrays.fill(eepromWipe, (byte) 0xff);
		for (int i = 0; i < 0x0200; i += 16) {
			writeToEEPROM(i, eepromWipe);
		}
	}
	
	@Override
	public void resetToolToFactory(int toolhead) {
		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();
		resetToolToBlank(toolhead); /// for generic S3G, just wipe the EEPROM
	}
	
	public void resetToolToBlank(int toolhead){
		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();

		byte eepromWipe[] = new byte[16];
		Arrays.fill(eepromWipe,(byte)0xff);
		for (int i = 0; i < 0x0200; i+=16) {
			writeToToolEEPROM(i,eepromWipe,toolhead);
		}
	}


	public EndstopType getInvertedEndstops() {
		checkEEPROM();
		byte[] b = readFromEEPROM(
				Sanguino3GEEPRPOM.EEPROM_ENDSTOP_INVERSION_OFFSET, 1);
		return EndstopType.endstopTypeForValue(b[0]);
	}

	public void setInvertedEndstops(EndstopType endstops) {
		byte b[] = new byte[1];
		b[0] = endstops.getValue();
		writeToEEPROM(Sanguino3GEEPRPOM.EEPROM_ENDSTOP_INVERSION_OFFSET, b);
	}

	public ExtraFeatures getExtraFeatures(int toolhead) {
		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();
		int efdat = read16FromToolEEPROM(Sanguino3GEEPRPOM.EC_EEPROM_EXTRA_FEATURES, 0x4084, toolhead);
		ExtraFeatures ef = new ExtraFeatures();
		ef.swapMotorController = (efdat & 0x0001) != 0;
		ef.heaterChannel = (efdat >> 2) & 0x0003;
		ef.hbpChannel = (efdat >> 4) & 0x0003;
		ef.abpChannel = (efdat >> 6) & 0x0003;
		// System.err.println("Extra features: smc "+Boolean.toString(ef.swapMotorController));
		// System.err.println("Extra features: ch ext "+Integer.toString(ef.heaterChannel));
		// System.err.println("Extra features: ch hbp "+Integer.toString(ef.hbpChannel));
		// System.err.println("Extra features: ch abp "+Integer.toString(ef.abpChannel));
		return ef;
	}
	
	public void setExtraFeatures(ExtraFeatures features, int toolhead) {
		/// toolhead -1 indicate auto-detect.Fast hack to get software out..
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();

		int efdat = 0x4000;
		if (features.swapMotorController) {
			efdat = efdat | 0x0001;
		}
		efdat |= features.heaterChannel << 2;
		efdat |= features.hbpChannel << 4;
		efdat |= features.abpChannel << 6;

		//System.err.println("Writing to EF: "+Integer.toHexString(efdat));
		writeToToolEEPROM(Sanguino3GEEPRPOM.EC_EEPROM_EXTRA_FEATURES,intToLE(efdat,2), toolhead);
	}

	public EstopType getEstopConfig() {
		checkEEPROM();
		byte[] b = readFromEEPROM(
				Sanguino3GEEPRPOM.EEPROM_ESTOP_CONFIGURATION_OFFSET, 1);
		return EstopType.estopTypeForValue(b[0]);
	}

	public void setEstopConfig(EstopType estop) {
		byte b[] = new byte[1];
		b[0] = estop.getValue();
		writeToEEPROM(Sanguino3GEEPRPOM.EEPROM_ESTOP_CONFIGURATION_OFFSET, b);
	}

	@Deprecated
	public double getPlatformTemperatureSetting() {
		return this.getPlatformTemperatureSetting(machine.currentTool().getIndex());
	}

	public double getPlatformTemperatureSetting(int toolhead) {
		/// toolhead -1 indicates auto-detect. Fast hack to get software out...
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();

		// This call was introduced in version 2.3
		if (toolVersion.atLeast(new Version(2, 3))) {
			PacketBuilder pb = new PacketBuilder(
					MotherboardCommandCode.TOOL_QUERY.getCode());
			pb.add8((byte) toolhead );
			pb.add8(ToolCommandCode.GET_PLATFORM_SP.getCode());
			PacketResponse pr = runQuery(pb.getPacket());
			int sp = pr.get16();
			machine.getTool(toolhead).setPlatformTargetTemperature(sp);
		}
		// super uses current toolhead, not specific toolhead
//		super.getPlatformTemperatureSetting();
		return machine.getTool(toolhead).getPlatformTargetTemperature();
	}

	@Deprecated
	public double getTemperatureSetting() {
		return this.getTemperatureSetting(machine.currentTool().getIndex());
	}

	public double getTemperatureSetting(int toolhead) {
		/// toolhead -1 indicates auto-detect. Fast hack to get software out...
		if(toolhead == -1 ) toolhead = machine.currentTool().getIndex();

		// This call was introduced in version 2.3
		if (toolVersion.atLeast(new Version(2, 3))) {
			PacketBuilder pb = new PacketBuilder(
					MotherboardCommandCode.TOOL_QUERY.getCode());
			pb.add8((byte) toolhead );
			pb.add8(ToolCommandCode.GET_SP.getCode());
			PacketResponse pr = runQuery(pb.getPacket());
			int sp = pr.get16();
			machine.getTool(toolhead).setTargetTemperature(sp);
		}
//		System.err.println(machine.getTool(toolhead).getTargetTemperature());
		// super uses current toolhead, not specific toolhead
		//super.getTemperatureSetting();
		return machine.getTool(toolhead).getTargetTemperature();
	}

	public Version getToolVersion() {
		return toolVersion;
	}

	public boolean setConnectedToolIndex(int index) {

		byte[] data = new byte[1];
		data[0] = (byte) index;
		// The broadcast address has changed. The safest solution is to try
		// both.
		writeToToolEEPROM(Sanguino3GEEPRPOM.EC_EEPROM_SLAVE_ID, data, 255); 
		///^  old firmware used 255, new fw ignores this 
		writeToToolEEPROM(Sanguino3GEEPRPOM.EC_EEPROM_SLAVE_ID, data, 127); 
		///^ new firmware used 127, old fw ignores this
		return false;
	}

	public boolean toolsCanBeReindexed() {
		return true;
	}

	public boolean supportsSimultaneousTools() {
		return true;
	}

	@Override
	public boolean hasVrefSupport() {
		return false;
	}

	@Override
	public void setStoredStepperVoltage(int stepperId, int referenceValue) {
		throw new UnsupportedOperationException("Store Stepper Voltage not supported in Sanguino3GDriver");
	}

	@Override
	public int getStoredStepperVoltage(int stepperId) {
		throw new UnsupportedOperationException("Get Stored Stepper Voltage not supported in Sanguino3GDriver");
	}

	@Override
	public EnumMap<AxisId, String> getAxisAlises() {
		/// Returns a set of Axes that are overridden or hijacked, 
		/// and a string to indicate what they are overridden or hijacked for.
		return new EnumMap<AxisId,String>(AxisId.class);
	}
	
	/// Returns true if this machine can verify the connected hardware
	/// is valid 
	@Override
	public boolean canVerifyMachine() { return false; } 
	
	/// Returns true if the connected machine is verified to be the 
	/// proper type
	@Override
	public boolean verifyMachineId() { return false; }

	@Override 
	public String getMachineType(){ return "MakerBot Sanguino"; } 
	
	/// Returns the number of tools as saved on the machine (not as per XML count)
	@Override 
	public int toolCountOnboard() { return 0; } 

	/// Returns true of tool count is save on the machine  (not as per XML count)
	@Override 
	public boolean hasToolCountOnboard() {return false; }

	/// Sets the number of tool count as saved on the machine (not as per XML count)
	@Override 
	public void setToolCountOnboard(int i){ }; 

	public boolean hasHbp(){return false;}

	@Override
	public byte currentHbpSetting() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public void setHbpSetting(boolean on_off) {
		// TODO Auto-generated method stub
		
	}
	
}
