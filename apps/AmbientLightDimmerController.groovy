/**
*  Ambient Light Dimmer Controller
*
*  Adjusts a dimmer level based on a target ambient light level
*    -Use a standard PI loop (velocity form) for the control algorithm
*    -Switch to Auto mode after the manipulated device is switched to On
*    -Manual mode off-delay option when the manipulated device (e.g. dimmer) is manually set
*    -Switch to Manual mode when the manipulated device is switched to Off
*
*  Copyright 2018 Guffman Ventures LLC
*  GNU General Public License v2 (https://www.gnu.org/licenses/gpl-2.0.txt)
*  Acknowledgement to someone in the community for the logging code segments (I can't remember who).
*/

definition(
    name: "Ambient Light Dimmer Controller",
    namespace: "Guffman",
    author: "Guffman",
    description: "Control a dimmer based on current ambient light conditions, using a PID algorithm.",
    category: "Convenience",
    importUrl: "https://raw.githubusercontent.com/guffman/Hubitat/master/apps/AmbientLightDimmerController.groovy"
)

preferences {
        page(name: "pageConfig") // Doing it this way elimiates the default app name/mode options.
}

def pageConfig() {
    dynamicPage(name: "", title: "", install: true, uninstall: true, refreshInterval:0) {
        section("General") 
        {
		    input (name: "customLabel", type: "text", title: "Enter a name for the app (optional)", required: false, defaultValue: "Ambient Light Controller")
		}
	    section("Devices")
		{
	            input "illuminanceSensor", "capability.illuminanceMeasurement", title: "Input - Illuminance Sensor:", required: true
		    input "dimmer", "capability.switchLevel", title: "Output - Dimmer:", required: true
                    input "dimmerController", "capability.holdableButton", title: "Dimmer Controller:", required: false
		}
        
		section("Lux Controller Settings")
		{
		    input "loopSpt", "number", title: "Illuminance Setpoint (lux):", required: true, defaultValue: 40	
                    input "loopKp", "decimal", title: "Controller Gain:", required: true, defaultValue: 1.0
                    input "loopKi", "decimal", title: "Controller Reset Time (minutes):", required: true, defaultValue: 0.25
                    input "loopOutputLowLimit", "number", title: "Minimum output in Controller Auto mode (%)", required: true, defaultValue: 5
                    input "loopOutputHighLimit", "number", title: "Maximum output in Controller Auto mode (%)", required: true, defaultValue: 100
                    input "loopOutputDeadband", "number", title: "Controller output deadband (%)", required: true, defaultValue: 2
                    input "loopOutputCycleLimit", "number", title: "Controller output change limit per cycle (%)", required: true, defaultValue: 10
                    input "loopExecInterval", "number", title: "Controller execution frequency (minutes):", required: true,  defaultValue: 1
	            input "loopManualTimeout", "number", title: "Timed return to Controller Auto mode after manual output adustment (minutes):", required: true,  defaultValue: 5
		    input "modes", "mode", title: "Select location mode(s) that disable controller Auto mode", multiple: true
		}
        
		section("Logging")
		{                       
			input(
				name: "logLevel"
				,title: "IDE logging level" 
				,multiple: false
				,required: true
				,type: "enum"
				,options: getLogLevels()
				,submitOnChange : false
				,defaultValue : "10"
				)  
		}
    }
}

def installed()
{
	initialize()
}

def updated()
{
	unsubscribe()
	initialize()
}

def initialize()
{
    infolog "Initializing"
    state.label = customLabel
    state.autoMode = true
    ds = dimmer.currentValue("switch")
    state.level = dimmer.currentValue("level").toFloat()
    debuglog "Initializing: dimmer switch state is ${ds}, level is ${state.level}"
    if (ds == "off") {
        debuglog "Initializing: loop set to Manual mode: dimmer switch is Off"
        state.autoMode = false
    }
    setModePermissive()
    state.pv = illuminanceSensor.currentValue("illuminance").toFloat()
    state.pvStr = illuminanceSensor.currentValue("illuminance").toString() + " lux"
    state.err = 0.0
    state.lastErr = 0.0
    state.co = state.level
    state.lastCo = state.level
    state.deltaCo = 0.0
    state.deltat = loopExecInterval.toFloat()
    subscribe(illuminanceSensor, "illuminance", illuminanceHandler)
    subscribe(dimmer, "level", dimmerLevelHandler)
    subscribe(dimmer, "switch", dimmerSwitchHandler)
    if (dimmerController) subscribe(dimmerController, "held", dimmerControllerHandler)
	subscribe(location, "mode", modeChangeHandler)
    unschedule()
    schedule("0 0/${loopExecInterval} * * * ?", execControlLoop)
}

def modeChangeHandler(evt)
{
    infolog "modeChangeHandler: mode changed: ${evt.value}"
    debuglog "modeChangeHandler: loop in Auto = ${state.autoMode}"
    setModePermissive()
}

def setModePermissive()
{
    debuglog "setModePermissive: location.mode = ${location.mode}"
    debuglog "setModePermissive: settings.modes = ${modes}"
    
	if(modes.contains(location.mode))
		{
			debuglog "setModePermissive: Entered a disabled mode, turning off Auto mode permissive"
			state.autoModePermissive = false
		}
	else
	{	
		debuglog "setModePermissive: Entered an enabled mode, turning on Auto mode permissive"
		state.autoModePermissive = true
	}
}

def illuminanceHandler(evt)
{
    infolog "illuminanceHandler: lux changed: ${evt.value}"
    debuglog "illuminanceHandler: Auto permissive = ${state.autoModePermissive}"
    debuglog "illuminanceHandler: loop in Auto = ${state.autoMode}"
    debuglog "illuminanceHandler: dimmer = ${state.level}"

    state.pv = evt.value.toFloat()
    state.pvStr = evt.value + " lux"
}

def execControlLoop()
{
    if (state.autoModePermissive && state.autoMode) {
        
        debuglog "execControlLoop: loop in Auto mode"

        // Compute new controller output delta
        state.co = state.level.toFloat()
        state.err = loopSpt - state.pv
        state.deltaCo = loopKp * (state.err - state.lastErr + (loopKi * state.deltat * state.err))
        state.lastErr = state.err
        
        debuglog "execControlLoop: loop in Auto mode: co=${state.co}"
        debuglog "execControlLoop: loop in Auto mode: pv=${state.pv}"
        debuglog "execControlLoop: loop in Auto mode: spt=${loopSpt}"
        debuglog "execControlLoop: loop in Auto mode: err=${state.err}"
        debuglog "execControlLoop: loop in Auto mode: deltaCo=${state.deltaCo}"
        
        // Evaluate controller output cycle limit and constrain if needed
        if (state.deltaCo > (1.0 * loopOutputCycleLimit)) {state.deltaCo = (1.0 * loopOutputCycleLimit)}
        if (state.deltaCo < (-1.0 * loopOutputCycleLimit)) {state.deltaCo = (-1.0 * loopOutputCycleLimit)}
        debuglog "execControlLoop: loop in Auto mode: after co cycle limit check: deltaCo=${state.deltaCo}"
        
        // Compute new controller output
        state.co += state.deltaCo
        debuglog "execControlLoop: loop in Auto mode: new co=${state.co}"
        // Controller output constraint checking
        if (state.co >= loopOutputHighLimit.toFloat()) {
            state.co = loopOutputHighLimit
        } else if (state.co <= loopOutputLowLimit.toFloat()) {
            state.co = loopOutputLowLimit
        }
        debuglog "execControlLoop: loop in Auto mode: after co limit check: new co=${state.co}"
        
        int ico = (int) Math.round(state.co)
        int deltalvl = ico - state.level.toInteger()
        
        // Make it so, only if the new co exceeds the output deadband relative to the current dimmer level
        if (deltalvl.abs() >= loopOutputDeadband) {
            setDimmerLevel(ico)
            debuglog "execControlLoop: output change of ${deltalvl} above deadband: set dimmer level to ${ico}"
        } else {
            debuglog "execControlLoop: output change of ${deltalvl} below deadband: skip setting dimmer level"
        }
        updateAppLabel("Auto mode - Output ${ico}%", "green", "${state.pvStr},")
    } else {
        // Track co in manual mode
        state.co = state.level.toFloat()
        updateAppLabel("Manual mode - Output ${state.level}%", "orange", "${state.pvStr},")
        debuglog "execControlLoop: loop in Manual mode - co tracking ${state.co}"
    }
}

def dimmerLevelHandler(evt)
{
    infolog "dimmerLevelHandler: Level changed: ${evt.value}"
    debuglog "dimmerLevelHandler: Auto permissive = ${state.autoModePermissive}"
	debuglog "dimmerLevelHandler: loop in Auto = ${state.autoMode}"
    debuglog "dimmerLevelHandler: illuminanceSensor = ${state.pv}"
    state.level = evt.value.toFloat()
}

def dimmerSwitchHandler(evt)
{
    infolog "dimmerSwitchHandler: Switch changed: ${evt.value}"
    debuglog "dimmerSwitchHandler: Auto permissive = ${state.autoModePermissive}"
	debuglog "dimmerSwitchHandler: loop mode = ${state.autoMode}"
    debuglog "dimmerSwitchHandler: illuminanceSensor = ${state.pv}"
    
	switch(evt.value)
	{
		case "on":
			if(loopManualTimeout == 0)
			{
				autoMode()
			} else {
                manualMode()
                debuglog "dimmerSwitchHandler: controller set to Manual mode, will return to Auto in ${loopManualTimeout} minutes"
				runIn(60 * loopManualTimeout.toInteger(), autoMode)
			}
			break
        case "off":
			manualMode()
            debuglog "dimmerSwitchHandler: dimmer switch off, controller set to Manual mode"
			break
    }
}

def dimmerControllerHandler(evt)
{
    infolog "dimmerControllerHandler: button held: ${evt.value}"
    debuglog "dimmerControllerHandler: Auto permissive = ${state.autoModePermissive}"
    debuglog "dimmerControllerHandler: loop in Auto = ${state.autoMode}"
    debuglog "dimmerControllerHandler: dimmer = ${state.level}"
    
    def btn = evt.value.toInteger()
    
    if (btn == 2 || btn == 4) {
		if(loopManualTimeout == 0) {
			autoMode()
		} else {
            manualMode()
            debuglog "dimmerControllerHandler: controller set to Manual mode, will return to Auto in ${loopManualTimeout} minutes"
			runIn(60 * loopManualTimeout.toInteger(), autoMode)
		}
    }
}


def turnOffDimmer()
{
    if(dimmer.currentValue("switch") == "on")
    {
        dimmer.off()
        debuglog "turnOffDimmer: Off"
    }
}

def setDimmerLevel(lvl)
{
    dimmer.setLevel(lvl, 30)
    debuglog "setDimmerLevel: ${lvl}"
}

def autoMode ()
{
    state.autoMode = true
    debuglog "autoMode: set"

}

def manualMode ()
{
    state.autoMode = false
    debuglog "manualMode: set"

}

//
// Utility functions
//
def debuglog(statement)
{   
	def logL = 0
    if (logLevel) logL = logLevel.toInteger()
    if (logL == 0) {return}//bail
    else if (logL >= 2)
	{
		log.debug(statement)
	}
}
def infolog(statement)
{       
	def logL = 0
    if (logLevel) logL = logLevel.toInteger()
    if (logL == 0) {return}//bail
    else if (logL >= 1)
	{
		log.info(statement)
	}
}
def getLogLevels(){
    return [["0":"None"],["1":"Running"],["2":"NeedHelp"]]
}

def updateAppLabel(textStr, textColor, textPrefix = '') {
  def str = """<span style='color:$textColor'> ($textPrefix $textStr)</span>"""
    app.updateLabel(customLabel + str)
}
