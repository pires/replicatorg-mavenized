/*
 GCodeParser.java

 Handles parsing GCode.
 
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

/* ___________________________________________________
 * @@@@@@@@@@@@@@@@@@@@@**^^""~~~"^@@^*@*@@**@@@@@@@@@
 * @@@@@@@@@@@@@*^^'"~   , - ' '; ,@@b. '  -e@@@@@@@@@
 * @@@@@@@@*^"~      . '     . ' ,@@@@(  e@*@@@@@@@@@@
 * @@@@@^~         .       .   ' @@@@@@, ~^@@@@@@@@@@@
 * @@@~ ,e**@@*e,  ,e**e, .    ' '@@@@@@e,  "*@@@@@'^@
 * @',e@@@@@@@@@@ e@@@@@@       ' '*@@@@@@    @@@'   0
 * @@@@@@@@@@@@@@@@@@@@@',e,     ;  ~^*^'    ;^~   ' 0
 * @@@@@@@@@@@@@@@^""^@@e@@@   .'           ,'   .'  @
 * @@@@@@@@@@@@@@'    '@@@@@ '         ,  ,e'  .    ;@
 * @@@@@@@@@@@@@' ,&&,  ^@*'     ,  .  i^"@e, ,e@e  @@
 * @@@@@@@@@@@@' ,@@@@,          ;  ,& !,,@@@e@@@@ e@@
 * @@@@@,~*@@*' ,@@@@@@e,   ',   e^~^@,   ~'@@@@@@,@@@
 * @@@@@@, ~" ,e@@@@@@@@@*e*@*  ,@e  @@""@e,,@@@@@@@@@
 * @@@@@@@@ee@@@@@@@@@@@@@@@" ,e@' ,e@' e@@@@@@@@@@@@@
 * @@@@@@@@@@@@@@@@@@@@@@@@" ,@" ,e@@e,,@@@@@@@@@@@@@@
 * @@@@@@@@@@@@@@@@@@@@@@@~ ,@@@,,0@@@@@@@@@@@@@@@@@@@
 * @@@@@@@@@@@@@@@@@@@@@@@@,,@@@@@@@@@@@@@@@@@@@@@@@@@
 * """""""""""""""""""""""""""""""""""""""""""""""""""
 * ~~~~~~~~~~~~WARNING: HERE BE DRAGONS ~~~~~~~~~~~~~~
 * 
 * Dragon from:
 * http://www.textfiles.com/artscene/asciiart/castles
 */

package replicatorg.app.gcode;

import java.util.EnumSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.logging.Level;

import javax.vecmath.Point3d;

import replicatorg.app.Base;
import replicatorg.app.exceptions.GCodeException;
import replicatorg.drivers.DriverQueryInterface;
import replicatorg.drivers.MultiTool;
import replicatorg.drivers.commands.DriverCommand;
import replicatorg.drivers.commands.DriverCommand.LinearDirection;
import replicatorg.machine.model.AxisId;
import replicatorg.machine.model.ToolheadAlias;
import replicatorg.util.Point5d;


public class GCodeParser {
	// our driver we use.
	protected DriverQueryInterface driver;
	
	/*
	 * We used to have all of this drilling code here.
	 * Over lunch we decided it didn't need to stay here.
	 * Now it's gone.
	 */
	
	// Arc drawing routine
	// Note: 5D is not supported
	Queue< DriverCommand > drawArc(Point5d center, Point5d endpoint, boolean clockwise) {
		// System.out.println("Arc from " + current.toString() + " to " +
		// endpoint.toString() + " with center " + center);

		Queue< DriverCommand > points = new LinkedList< DriverCommand >();
		
		// angle variables.
		double angleA;
		double angleB;
		double angle;
		double radius;
		double length;

		// delta variables.
		double aX;
		double aY;
		double bX;
		double bY;

		// figure out our deltas
		Point5d current = driver.getCurrentPosition(false);
		aX = current.x() - center.x();
		aY = current.y() - center.y();
		bX = endpoint.x() - center.x();
		bY = endpoint.y() - center.y();

		// Clockwise
		if (clockwise) {
			angleA = Math.atan2(bY, bX);
			angleB = Math.atan2(aY, aX);
		}
		// Counterclockwise
		else {
			angleA = Math.atan2(aY, aX);
			angleB = Math.atan2(bY, bX);
		}

		// Make sure angleB is always greater than angleA
		// and if not add 2PI so that it is (this also takes
		// care of the special case of angleA == angleB,
		// ie we want a complete circle)
		if (angleB <= angleA)
			angleB += 2 * Math.PI;
		angle = angleB - angleA;
		// calculate a couple useful things.
		radius = Math.sqrt(aX * aX + aY * aY);
		length = radius * angle;

		// for doing the actual move.
		int steps;
		int s;
		int step;

		// Maximum of either 2.4 times the angle in radians
		// or the length of the curve divided by the curve section constant
		steps = (int) Math.ceil(Math.max(angle * 2.4, length / curveSection));

		// this is the real draw action.
		Point5d newPoint = new Point5d(current);
		double arcStartZ = current.z();
		for (s = 1; s <= steps; s++) {
			// Forwards for CCW, backwards for CW
			if (!clockwise)
				step = s;
			else
				step = steps - s;

			// calculate our waypoint.
			newPoint.setX(center.x() + radius * Math.cos(angleA + angle * ((double) step / steps)));
			newPoint.setY(center.y() + radius * Math.sin(angleA + angle * ((double) step / steps)));
			newPoint.setZ(arcStartZ + (endpoint.z() - arcStartZ) * s / steps);

			// start the move
			points.add(new replicatorg.drivers.commands.QueuePoint(newPoint));
		}
		
		return points;
	}
	
	// our curve section variables.
	public static double curveSectionMM = Base.preferences.getDouble("replicatorg.parser.curve_segment_mm", 1.0);
	public static double curveSectionInches = curveSectionMM / 25.4;

	protected double curveSection = 0.0;

	// our offset variables 0 = master, 1-6 = offsets 1-6
	protected Point3d currentOffset;

	// false = incremental; true = absolute
	boolean absoluteMode = false;

	// our feedrate variables.
	/**
	 * Feedrate in mm/minute.
	 */
	double feedrate = 0.0;

	// current selected tool
	protected int tool = ToolheadAlias.SINGLE.number;

	// unit variables.
	public static int UNITS_MM = 0;

	public static int UNITS_INCHES = 1;

	protected int units;
	
	/**
	 * Creates the driver object.
	 */
	public GCodeParser() {
		// we default to millimeters
		units = UNITS_MM;
		curveSection = curveSectionMM;

		// init our offset
		currentOffset = new Point3d();
	}

	/**
	 * Get the maximum feed rate from the driver's model.
	 */
	protected double getMaxFeedrate() {
		// TODO: right now this is defaulting to the x feedrate. We should
		// eventually check for z-axis motions and use that feedrate. We should
		// also either alter the model, or post a warning when the x and y
		// feedrates differ.
		return driver.getMaximumFeedrates().x();
	}

	/**
	 * initialize parser with values from the driver
	 */
	public void init(DriverQueryInterface drv) {
		// our driver class
		driver = drv;
		
		// init our offset variables
		currentOffset = driver.getOffset(0);
	}

	/**
	 * Function parses a line of GCode, packages that line into an executable event
	 * for the s3g driver code to execute, and queues the event for execution
	 * 
	 * @param cmd a single line of GCode to parse, package, and send to the driver.
	 */
	public boolean parse(String cmd, Queue< DriverCommand > commandQueue) {
		
		// First, parse the GCode string into an object we can query.
		GCodeCommand gcode = new GCodeCommand(cmd);

		// Now, convert the GCode instruction into a series of driver commands,
		// that will be executed by execute()
		
		// If our driver is in pass-through mode, just put the string in a buffer and we are done.
		if (driver.isPassthroughDriver()) {
			commandQueue.add(new replicatorg.drivers.commands.GCodePassthrough(gcode.getCommand()));
		}
		else {
			try {
				if (gcode.hasCode('G')) {
					buildGCodes(gcode, commandQueue);
				}
				else if (gcode.hasCode('M')) {
					buildMCodes(gcode, commandQueue);
				} else if (gcode.hasCode('T'))	{
					buildTCodes(gcode, commandQueue);
				}
			} catch (GCodeException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
		return true;
	}


	private double convertToMM(double value, int units) {
		if (units == UNITS_INCHES) {
			return value * 25.4;
		}
		return value;
	}

	private EnumSet<AxisId> getAxes(GCodeCommand gcode) {
		EnumSet<AxisId> axes = EnumSet.noneOf(AxisId.class);

		if (gcode.hasCode('X')) axes.add(AxisId.X);
		if (gcode.hasCode('Y')) axes.add(AxisId.Y);
		if (gcode.hasCode('Z')) axes.add(AxisId.Z);
		if (gcode.hasCode('A')) axes.add(AxisId.A);
		if (gcode.hasCode('B')) axes.add(AxisId.B);
		
		return axes;
	}
	
	private void buildTCodes(GCodeCommand gcode, Queue< DriverCommand > commands) throws GCodeException 
	{
		// M6 was historically used to wait for toolheads to get up to temperature, so
		// you may wish to avoid using M6 by using T
		if (driver instanceof MultiTool &&  ((MultiTool)driver).supportsSimultaneousTools() )
			throw new GCodeException("the current driver" + driver.toString() + " does not support multipleTools" );

		tool = (int) gcode.getCodeValue('T');
		commands.add(new replicatorg.drivers.commands.SelectTool(tool));
		currentOffset = driver.getOffset(tool+1);
	}

	private void buildMCodes(GCodeCommand gcode, Queue< DriverCommand > commands) throws GCodeException {
		// If this machine handles multiple active toolheads, we always honor a T code
		// as being a annotation to send the given command to the given toolheads.  Be
		// aware that appending a T code to an M code will not necessarily generate a
		// change tool request!  Use M6 for that.
		if (gcode.hasCode('T') && driver instanceof MultiTool && ((MultiTool)driver).supportsSimultaneousTools())
		{
			commands.add(new replicatorg.drivers.commands.SelectTool((int) gcode.getCodeValue('T')));
			tool = (int) gcode.getCodeValue('T');
		}
		
		// handle unrecognised GCode
		if(GCodeEnumeration.getGCode("M", (int)gcode.getCodeValue('M')) == null)
		{
			String message = "Unrecognized MCode! M" + (int)gcode.getCodeValue('M');
			Base.logger.log(Level.SEVERE, message);
			throw new GCodeException(message);
		}
		
		switch (GCodeEnumeration.getGCode("M", (int)gcode.getCodeValue('M'))) {
		case M0:
			// M0 == unconditional halt
			commands.add(new replicatorg.drivers.commands.WaitUntilBufferEmpty());
			commands.add(new replicatorg.drivers.commands.UnconditionalHalt(gcode.getComment()));
			break;
		case M1:
			// M1 == optional halt
			commands.add(new replicatorg.drivers.commands.WaitUntilBufferEmpty());
			commands.add(new replicatorg.drivers.commands.OptionalHalt(gcode.getComment()));
			break;
		case M2:
			// M2 == program end
			commands.add(new replicatorg.drivers.commands.WaitUntilBufferEmpty());
			commands.add(new replicatorg.drivers.commands.ProgramEnd(gcode.getComment()));
			break;
		case M30:
			commands.add(new replicatorg.drivers.commands.WaitUntilBufferEmpty());
			commands.add(new replicatorg.drivers.commands.ProgramRewind(gcode.getComment()));
			break;
		// spindle on, CW
		case M3:
			commands.add(new replicatorg.drivers.commands.SetSpindleDirection(DriverCommand.AxialDirection.CLOCKWISE));
			commands.add(new replicatorg.drivers.commands.EnableSpindle());
			break;
		// spindle on, CCW
		case M4:
			commands.add(new replicatorg.drivers.commands.SetSpindleDirection(DriverCommand.AxialDirection.COUNTERCLOCKWISE));
			commands.add(new replicatorg.drivers.commands.EnableSpindle());
			break;
		// spindle off
		case M5:
			commands.add(new replicatorg.drivers.commands.DisableSpindle());
			break;
		// tool change.
		case M6:
			int timeout = 65535;
			if (gcode.hasCode('P')) {
				timeout = (int)gcode.getCodeValue('P');
			}
			if (gcode.hasCode('T')) {
				commands.add(new replicatorg.drivers.commands.RequestToolChange((int) gcode.getCodeValue('T'), timeout));
			}
			else {
				throw new GCodeException("The T parameter is required for tool changes. (M6)");
			}
			break;
		// coolant A on (flood coolant)
		case M7:
			commands.add(new replicatorg.drivers.commands.EnableFloodCoolant());
			break;
		// coolant B on (mist coolant)
		case M8:
			commands.add(new replicatorg.drivers.commands.EnableMistCoolant());
			break;
		// all coolants off
		case M9:
			commands.add(new replicatorg.drivers.commands.DisableFloodCoolant());
			commands.add(new replicatorg.drivers.commands.DisableMistCoolant());
			break;
		// close clamp
		case M10:
			if (gcode.hasCode('Q'))
				commands.add(new replicatorg.drivers.commands.CloseClamp((int) gcode.getCodeValue('Q')));
			else
				throw new GCodeException(
						"The Q parameter is required for clamp operations. (M10)");
			break;
		// open clamp
		case M11:
			if (gcode.hasCode('Q'))
				commands.add(new replicatorg.drivers.commands.OpenClamp((int) gcode.getCodeValue('Q')));
			else
				throw new GCodeException(
						"The Q parameter is required for clamp operations. (M11)");
			break;
		// spindle CW and coolant A on
		case M13:
			commands.add(new replicatorg.drivers.commands.SetSpindleDirection(DriverCommand.AxialDirection.CLOCKWISE));
			commands.add(new replicatorg.drivers.commands.EnableSpindle());
			commands.add(new replicatorg.drivers.commands.EnableFloodCoolant());
			break;
		// spindle CCW and coolant A on
		case M14:
			commands.add(new replicatorg.drivers.commands.SetSpindleDirection(DriverCommand.AxialDirection.COUNTERCLOCKWISE));
			commands.add(new replicatorg.drivers.commands.EnableSpindle());
			commands.add(new replicatorg.drivers.commands.EnableFloodCoolant());
			break;
		// enable drives
		case M17:
			{ //these braces provide a new level of scope to avoid name clash on axes
				EnumSet<AxisId> axes = getAxes(gcode);
				if (axes.isEmpty()) {
					commands.add(new replicatorg.drivers.commands.EnableDrives());
				} else {
					commands.add(new replicatorg.drivers.commands.EnableAxes(axes));
				}
			}
				break;
		// disable drives
		case M18:
			{ //these braces provide a new level of scope to avoid name clash on axes
				EnumSet<AxisId> axes = getAxes(gcode);
				if (axes.isEmpty()) {
					commands.add(new replicatorg.drivers.commands.DisableDrives());
				} else {
					commands.add(new replicatorg.drivers.commands.DisableAxes(axes));
				}
			}
			break;
		// open collet
		case M21:
			commands.add(new replicatorg.drivers.commands.OpenCollet());
			break;
			// open collet
		case M22:
			commands.add(new replicatorg.drivers.commands.CloseCollet());
			break;
			// M40-M46 = change gear ratios
		case M40:
			commands.add(new replicatorg.drivers.commands.ChangeGearRatio(0));
			break;
		case M41:
			// driver.changeGearRatio(1);
			commands.add(new replicatorg.drivers.commands.ChangeGearRatio(1));
			break;
		case M42:
			commands.add(new replicatorg.drivers.commands.ChangeGearRatio(2));
			break;
		case M43:
			commands.add(new replicatorg.drivers.commands.ChangeGearRatio(3));
			break;
		case M44:
			commands.add(new replicatorg.drivers.commands.ChangeGearRatio(4));
			break;
		case M45:
			commands.add(new replicatorg.drivers.commands.ChangeGearRatio(5));
			break;
		case M46:
			commands.add(new replicatorg.drivers.commands.ChangeGearRatio(6));
			break;
		// read spindle speed
		case M50:
			driver.getSpindleRPM();
			break;
			// turn extruder on, forward
		case M70:
			// print message			
			if (gcode.hasCode('P'))
				commands.add(new replicatorg.drivers.commands.DisplayMessage(gcode.getCodeValue('P'),gcode.getComment(), false));
			else
				commands.add(new replicatorg.drivers.commands.DisplayMessage(0,gcode.getComment(), false));
			
			break;
		case M71:
			// User-clearable pause
			// First send message, if any...
			if (gcode.getComment().length() > 0) {
				commands.add(new replicatorg.drivers.commands.DisplayMessage(0,gcode.getComment(), true));
			} else {
				commands.add(new replicatorg.drivers.commands.DisplayMessage(0,"Paused, press button\nto continue", true));
			}
			// ...then send user pause command. 
			//commands.add(new replicatorg.drivers.commands.UserPause(gcode.getCodeValue('P'),true,0xff));
			break;
		case M72:
			// Play a tone or song as stored on the machine
			commands.add(new replicatorg.drivers.commands.PlaySong(gcode.getCodeValue('P')) );
			break;
		case M73:
			// Manually sets the percent complete info on the bot.
			commands.add(new replicatorg.drivers.commands.SetBuildPercent(gcode.getCodeValue('P'), gcode.getComment() ) );
			break;			
		case M101:
			commands.add(new replicatorg.drivers.commands.SetMotorDirection(DriverCommand.AxialDirection.CLOCKWISE));
			commands.add(new replicatorg.drivers.commands.EnableExtruderMotor());
			break;
		// turn extruder on, reverse
		case M102:
			commands.add(new replicatorg.drivers.commands.SetMotorDirection(DriverCommand.AxialDirection.COUNTERCLOCKWISE));
			commands.add(new replicatorg.drivers.commands.EnableExtruderMotor());
			break;
		// turn extruder off
		case M103:
			commands.add(new replicatorg.drivers.commands.DisableMotor());
			break;
		// custom code for temperature control
		case M104:
			if (gcode.hasCode('S'))
				commands.add(new replicatorg.drivers.commands.SetTemperature(gcode.getCodeValue('S')));
			break;
		// custom code for temperature reading
		// TODO: This command seems like a hack, it would be better for the driver to poll temperature rather than
		//       have the gcode ask for it.
		case M105:
			commands.add(new replicatorg.drivers.commands.ReadTemperature());
			break;
		// turn AutomatedBuildPlatform on
		case M106:
			if(driver.hasAutomatedBuildPlatform())
				commands.add(new replicatorg.drivers.commands.ToggleAutomatedBuildPlatform(true));
			else
				commands.add(new replicatorg.drivers.commands.EnableFan());
			break;
		// turn AutomatedBuildPlatform off
		case M107:
			if(driver.hasAutomatedBuildPlatform())
				commands.add(new replicatorg.drivers.commands.ToggleAutomatedBuildPlatform(false));
			else
				commands.add(new replicatorg.drivers.commands.DisableFan());
			break;
		// set max extruder speed, RPM
		case M108:
			if (gcode.hasCode('S'))
				commands.add(new replicatorg.drivers.commands.SetMotorSpeedPWM((int)gcode.getCodeValue('S')));
			else if (gcode.hasCode('R'))
				commands.add(new replicatorg.drivers.commands.SetMotorSpeedRPM(gcode.getCodeValue('R')));
			break;
		// set build platform temperature
		case M109:
		case M140: // skeinforge chamber code for HBP
			if (gcode.hasCode('S'))
				commands.add(new replicatorg.drivers.commands.SetPlatformTemperature(gcode.getCodeValue('S')));
			break;
		// set build chamber temperature
		case M110:
			commands.add(new replicatorg.drivers.commands.SetChamberTemperature(gcode.getCodeValue('S')));
			break;
		// valve open
		case M126:
			commands.add(new replicatorg.drivers.commands.OpenValve());
			break;
		// valve close
		case M127:
			commands.add(new replicatorg.drivers.commands.CloseValve());
			break;
		// where are we?
		case M128:
			commands.add(new replicatorg.drivers.commands.GetPosition());
			break;
		// Instruct the machine to store it's current position to EEPROM
		case M131:
			{ //these braces provide a new level of scope to avoid name clash on axes
				EnumSet<AxisId> axes = getAxes(gcode);
				commands.add(new replicatorg.drivers.commands.StoreHomePositions(axes));
			}
			break;
		// Instruct the machine to restore it's current position from EEPROM
		case M132:
			{ //these braces provide a new level of scope to avoid name clash on axes
				EnumSet<AxisId> axes = getAxes(gcode);
				commands.add(new replicatorg.drivers.commands.RecallHomePositions(axes));
				commands.add(new replicatorg.drivers.commands.WaitUntilBufferEmpty());
			}
			break;
		//Silently ignore these
		case M141: // skeinforge chamber plugin chamber temperature code
		case M142: // skeinforge chamber plugin holding pressure code
			break;
		
		// initialize to default state.
		case M200:
			commands.add(new replicatorg.drivers.commands.Initialize());
			break;
		// set servo 1 position
		case M300:
			if (gcode.hasCode('S')) {
				commands.add(new replicatorg.drivers.commands.SetServo(0, gcode.getCodeValue('S')));
			}
			break;
		// set servo 2 position
		case M301:
			if (gcode.hasCode('S')) {
				commands.add(new replicatorg.drivers.commands.SetServo(1, gcode.getCodeValue('S')));
			}
			break;
		// Start data capture
		case M310:
			commands.add(new replicatorg.drivers.commands.WaitUntilBufferEmpty());
			commands.add(new replicatorg.drivers.commands.StartDataCapture(gcode.getComment()));
			break;
			
		// Stop data capture
		case M311:
			commands.add(new replicatorg.drivers.commands.WaitUntilBufferEmpty());
			commands.add(new replicatorg.drivers.commands.StopDataCapture());
			break;

		// Log a note to the data capture store
		case M312:
			commands.add(new replicatorg.drivers.commands.WaitUntilBufferEmpty());
			commands.add(new replicatorg.drivers.commands.DataCaptureNote(gcode.getComment()));
			break;
		// Acceleration on
		case M320:
			commands.add(new replicatorg.drivers.commands.SetAccelerationToggle(true));
			break;
		// Acceleration off
		case M321:
			commands.add(new replicatorg.drivers.commands.SetAccelerationToggle(false));
			break;
		default:
			throw new GCodeException("Unknown M code: M" + (int) gcode.getCodeValue('M'));
		}
	}

	private void buildGCodes(GCodeCommand gcode, Queue< DriverCommand > commands) throws GCodeException {
		if (! gcode.hasCode('G')) {
			throw new GCodeException("Not a G code!");
		}
		
		// start us off at our current position...
		Point5d pos = driver.getCurrentPosition(false);

		// initialize our points, etc.
		double iVal = convertToMM(gcode.getCodeValue('I'), units); // / X offset
																// for arcs
		double jVal = convertToMM(gcode.getCodeValue('J'), units); // / Y offset
																// for arcs
		@SuppressWarnings("unused")
		double kVal = convertToMM(gcode.getCodeValue('K'), units); // / Z offset
																// for arcs
		@SuppressWarnings("unused")
		double qVal = convertToMM(gcode.getCodeValue('Q'), units); // / feed
																// increment for
																// G83
		double rVal = convertToMM(gcode.getCodeValue('R'), units); // / arc radius
		double xVal = convertToMM(gcode.getCodeValue('X'), units); // / X units
		double yVal = convertToMM(gcode.getCodeValue('Y'), units); // / Y units
		double zVal = convertToMM(gcode.getCodeValue('Z'), units); // / Z units
		double aVal = convertToMM(gcode.getCodeValue('A'), units); // / A units
		double bVal = convertToMM(gcode.getCodeValue('B'), units); // / B units
		// Note: The E axis is treated internally as the A or B axis
		double eVal = convertToMM(gcode.getCodeValue('E'), units); // / E units

		// adjust for our offsets
		xVal += currentOffset.x;
		yVal += currentOffset.y;
		zVal += currentOffset.z;

		// absolute just specifies the new position
		if (absoluteMode) {
			if (gcode.hasCode('X'))
				pos.setX(xVal);
			if (gcode.hasCode('Y'))
				pos.setY(yVal);
			if (gcode.hasCode('Z'))
				pos.setZ(zVal);
			if (gcode.hasCode('A'))
				pos.setA(aVal);
			if (gcode.hasCode('E')) {
			  // can't assume tool 0 == a, it's configurable in machine.xml!
				if (driver.getMachine().getTool(tool).getMotorStepperAxis().name() == "B") {
          // Base.logger.warning("Mapping axis E to axis: " + driver.getMachine().getTool(tool).getMotorStepperAxis().name());
					pos.setB(eVal);
				} else {
          // Base.logger.warning("Mapping axis E to axis: " + driver.getMachine().getTool(tool).getMotorStepperAxis().name());
					pos.setA(eVal);
				}
			}
			if (gcode.hasCode('B'))
				pos.setB(bVal);
		}
		// relative specifies a delta
		else {
			if (gcode.hasCode('X'))
				pos.setX(pos.x() + xVal);
			if (gcode.hasCode('Y'))
				pos.setY(pos.y() + yVal);
			if (gcode.hasCode('Z'))
				pos.setZ(pos.z() + zVal);
			if (gcode.hasCode('A'))
				pos.setA(pos.a() + aVal);
			if (gcode.hasCode('E')) {
			  // can't assume tool 0 == a, it's configurable in machine.xml!
				if (driver.getMachine().getTool(tool).getMotorStepperAxis().name() == "B") {
          // Base.logger.warning("Mapping axis E to axis: " + driver.getMachine().getTool(tool).getMotorStepperAxis().name());
					pos.setB(pos.b() + eVal);
				} else {
          // Base.logger.warning("Mapping axis E to axis: " + driver.getMachine().getTool(tool).getMotorStepperAxis().name());
					pos.setA(pos.a() + eVal);
				}
			}
			if (gcode.hasCode('B'))
				pos.setB(pos.b() + bVal);
		}

		// Get feedrate if supplied
		if (gcode.hasCode('F')) {
			// Read feedrate in mm/min.
			feedrate = gcode.getCodeValue('F');
			
			// TODO: Why do we do this here, and not in individual commands?
			commands.add(new replicatorg.drivers.commands.SetFeedrate(feedrate));
		}
		

		GCodeEnumeration codeEnum = GCodeEnumeration.getGCode("G", (int)gcode.getCodeValue('G'));

		// handle unrecognised GCode
		if(codeEnum == null)
		{
			String message = "Unrecognized GCode! G" + (int)gcode.getCodeValue('G');
			Base.logger.log(Level.SEVERE, message);
			throw new GCodeException(message);
		}
		
		switch (codeEnum) {
		// these are basically the same thing, but G0 is supposed to do it as quickly as possible.
		// Rapid Positioning
		case G0:
			if (gcode.hasCode('F')) {
				// Allow user to explicitly override G0 feedrate if they so desire.
				commands.add(new replicatorg.drivers.commands.SetFeedrate(feedrate));
			} else {
				// Compute the most rapid possible rate for this move.
				Point5d diff = driver.getCurrentPosition(false);
				diff.sub(pos);
				diff.absolute();
				double length = diff.length();
				double selectedFR = Double.MAX_VALUE;
				Point5d maxFR = driver.getMaximumFeedrates();
				// Compute the feedrate using assuming maximum feed along each axis, and select
				// the slowest option.
				for (int idx = 0; idx < 3; idx++) {
					double axisMove = diff.get(idx);
					if (axisMove == 0) { continue; }
					double candidate = maxFR.get(idx)*length/axisMove;
					if (candidate < selectedFR) {
						selectedFR = candidate;
					}
				}
				// Add a sane default for the null move, just in case.
				if (selectedFR == Double.MAX_VALUE) { selectedFR = maxFR.get(0); }  
				commands.add(new replicatorg.drivers.commands.SetFeedrate(selectedFR));
			}				
			commands.add(new replicatorg.drivers.commands.QueuePoint(pos));
			break;
		// Linear Interpolation
		case G1:
			// set our target.
			commands.add(new replicatorg.drivers.commands.SetFeedrate(feedrate));
			commands.add(new replicatorg.drivers.commands.QueuePoint(pos));
			break;
		// Clockwise arc
		case G2:
			// Counterclockwise arc
		case G3: {
			// call our arc drawing function.
			// Note: We don't support 5D
			if (gcode.hasCode('I') || gcode.hasCode('J')) {
				// our centerpoint
				Point5d center = new Point5d();
				Point5d current = driver.getCurrentPosition(false);
				center.setX(current.x() + iVal);
				center.setY(current.y() + jVal);

				// Get the points for the arc
				if (codeEnum == GCodeEnumeration.G2)
					commands.addAll(drawArc(center, pos, true));
				else
					commands.addAll(drawArc(center, pos, false));
			}
			// or we want a radius based one
			else if (gcode.hasCode('R')) {
				throw new GCodeException("G02/G03 arcs with (R)adius parameter are not supported yet.");
			}
		}
			break;
		// dwell
		case G4:
			commands.add(new replicatorg.drivers.commands.Delay((long)gcode.getCodeValue('P')));
			break;
		case G10:
			if (gcode.hasCode('P')) {
				int offsetSystemNum = ((int)gcode.getCodeValue('P'));
				if (offsetSystemNum >= 1 && offsetSystemNum <= 6) {
					if (gcode.hasCode('X')) 
						commands.add(new replicatorg.drivers.commands.SetAxisOffset(AxisId.X, offsetSystemNum, gcode.getCodeValue('X')));
					if (gcode.hasCode('Y')) 
						commands.add(new replicatorg.drivers.commands.SetAxisOffset(AxisId.Y, offsetSystemNum, gcode.getCodeValue('Y')));
					if (gcode.hasCode('Z')) 
						commands.add(new replicatorg.drivers.commands.SetAxisOffset(AxisId.Z, offsetSystemNum, gcode.getCodeValue('Z')));
				}
			}
			else 
				Base.logger.warning("No coordinate system indicated use G10 Pn, where n is 0-6.");
			break;
		// Inches for Units
		case G20:
		case G70:
			units = UNITS_INCHES;
			curveSection = curveSectionInches;
			break;
		// mm for Units
		case G21:
		case G71:
			units = UNITS_MM;
			curveSection = curveSectionMM;
			break;
		// This should be "return to home".  We need to introduce new GCodes for homing.
			//replaced by G161, G162
		case G28:
			{
				// home all axes?
				EnumSet<AxisId> axes = getAxes(gcode);
				
				if (gcode.hasCode('F')) {
					commands.add(new replicatorg.drivers.commands.HomeAxes(axes, LinearDirection.POSITIVE, feedrate));
				}
				else {
					commands.add(new replicatorg.drivers.commands.HomeAxes(axes, LinearDirection.POSITIVE));
				}
			}
			break;
		// home negative.
		case G161:
			{
				// home all axes?
				EnumSet<AxisId> axes = getAxes(gcode);
				
				if (gcode.hasCode('F')) {
					commands.add(new replicatorg.drivers.commands.HomeAxes(axes, LinearDirection.NEGATIVE, feedrate));
				}
				else {
					commands.add(new replicatorg.drivers.commands.HomeAxes(axes, LinearDirection.NEGATIVE));
				}
			}
			break;
			// home positive.
		case G162:
			{
				// home all axes?
				EnumSet<AxisId> axes = getAxes(gcode);
				if (gcode.hasCode('F')) {
					commands.add(new replicatorg.drivers.commands.HomeAxes(axes, LinearDirection.POSITIVE, feedrate));
				}
				else {
					commands.add(new replicatorg.drivers.commands.HomeAxes(axes, LinearDirection.POSITIVE));
				}
			}
			break;
		// master offset
		case G53:
			currentOffset = driver.getOffset(0);
			break;
		// fixture offset 1
		case G54:
			currentOffset = driver.getOffset(1);
			break;
		// fixture offset 2
		case G55:
			currentOffset = driver.getOffset(2);
			break;
		// fixture offset 3
		case G56:
			currentOffset = driver.getOffset(3);
			break;
		// fixture offset 4
		case G57:
			currentOffset = driver.getOffset(4);
			break;
		// fixture offset 5
		case G58:
			currentOffset = driver.getOffset(5);
			break;
		// fixture offset 6
		case G59:
			currentOffset = driver.getOffset(6);
			break;
		// Absolute Positioning
		case G90:
			absoluteMode = true;
			break;
		// Incremental Positioning
		case G91:
			absoluteMode = false;
			break;
		// Set position
		case G92:
			Point5d current = driver.getCurrentPosition(false);

			if (gcode.hasCode('X'))
				current.setX(xVal);
			if (gcode.hasCode('Y'))
				current.setY(yVal);
			if (gcode.hasCode('Z'))
				current.setZ(zVal);
			if (gcode.hasCode('A'))
				current.setA(aVal);
			if (gcode.hasCode('E')) {
			  // can't assume tool 0 == a, it's configurable in machine.xml!			  
				if (driver.getMachine().getTool(tool).getMotorStepperAxis().name() == "B") {
          // Base.logger.warning("Resetting position of axis E to axis: " + driver.getMachine().getTool(tool).getMotorStepperAxis().name());          
					current.setB(eVal);
				} else {
          // Base.logger.warning("Resetting position of axis E to axis: " + driver.getMachine().getTool(tool).getMotorStepperAxis().name());          
					current.setA(eVal);
				}
			}
			if (gcode.hasCode('B'))
				current.setB(bVal);
			
			commands.add(new replicatorg.drivers.commands.SetCurrentPosition(current));
			break;
//		 feed rate mode
//		 case G93: //inverse time feed rate
//		case G94: // IPM feed rate (our default)
//			 case G95: //IPR feed rate
//			 TODO: make this work.
//			break;
		// spindle speed rate
		case G97:
			commands.add(new replicatorg.drivers.commands.SetSpindleRPM(gcode.getCodeValue('S')));
			break;	
		case G130:
			/// TODO:  axis ids should not be hard coded
			if (gcode.hasCode('X'))
				commands.add(new replicatorg.drivers.commands.SetStepperVoltage(0, (int)gcode.getCodeValue('X')));
			if (gcode.hasCode('Y'))
				commands.add(new replicatorg.drivers.commands.SetStepperVoltage(1, (int)gcode.getCodeValue('Y')));
			if (gcode.hasCode('Z'))
				commands.add(new replicatorg.drivers.commands.SetStepperVoltage(2, (int)gcode.getCodeValue('Z')));
			if (gcode.hasCode('A'))
				commands.add(new replicatorg.drivers.commands.SetStepperVoltage(3, (int)gcode.getCodeValue('A')));
			if (gcode.hasCode('B'))
				commands.add(new replicatorg.drivers.commands.SetStepperVoltage(4, (int)gcode.getCodeValue('B')));
				break;
		// error, error!
		default:
			throw new GCodeException("Unknown G code: G"
					+ (int) gcode.getCodeValue('G'));
		}
	}
}
