/*
*  Smart Dewpoint Controller
*
*  Adjusts a switch/outlet based on a target high / low dewpoint 
*    -Use a standard differential gap algorithm for the control algorithm
*
*  Copyright 2019 Guffman Ventures LLC
*  GNU General Public License v2 (https://www.gnu.org/licenses/gpl-2.0.txt)
*
*  Change History:
*
*    Date        Who            What
*    ----        ---            ----
*    2019-12-25  Marc Chevis    Original Creation
*    2020-07-25  Marc Chevis    Added features for feedback indicators, Thermostat association and related fan controls.
*    2020-10-04  Marc Chevis    Revised thermostat fan mode to follow dehumidifying power feedback in lieu of control output. 
*    2020-10-23  Marc Chevis    Revised Smart Humidistat app to control dewpoint
*    2021-05-21  Marc Chevis    Removed unused features
*    2021-05-29  Marc Chevis    Added timed inhibit feature to prevent dehumidifer running when A/C is in cooling operating state.
*    2021-06-05  Marc Chevis    Cleaned up some logging inconsistencies, added elapsed time-in-state calcs for thermostat
*
*
*/

def version() {"v0.3"}

definition(
    name: "Smart Dewpoint Controller",
    namespace: "Guffman",
    author: "Guffman",
    description: "Control a switch/outlet to drive dewpoint to within a set high and low limit, using a differential gap control algorithm.",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/guffman/Hubitat/master/apps/SmartDewpointController.groovy"
)
preferences {
    page(name: "pageConfig") // Doing it this way eliminates the default app name/mode options.
}

def pageConfig() {
    dynamicPage(name: "", title: "", install: true, uninstall: true, refreshInterval:0) {
        section("General") 
        {
		input (name: "customLabel", type: "text", title: "Enter a name for the app (optional)", required: false, defaultValue: "Smart Dewpoint Controller")
		}
	    section("Gap Controller Input/Output Devices")
		{
            input "loopSpt", "capability.temperatureMeasurement", title: "Dewpoint Setpoint:", required: true, multiple: false
			input "tempSensor", "capability.temperatureMeasurement", title: "Dewpoint Sensor:", required: true, multiple: false
			input "humControlSwitch", "capability.switch", title: "Dehumidify Control Output - Switch or Outlet:", required: true, multiple: false
            input "fanControlSwitch", "capability.switch", title: "Fan Control Output - Switch or Outlet:", required: false
		}
		section("Gap Controller Settings")
		{
            input "loopDband", "decimal", title: "Deadband (°F)", required: true, range: "0.5..3.0", defaultValue: 1.0
            input "onDelay", "number", title: "Dehumidify Control Output On/Off Delay(mins)", required: true, range: "0..30", defaultValue: 1
            input "minOnTime", "number", title: "Dehumidify Control Minimum On Time (mins)", required: true, range: "5..30", defaultValue: 5
            input "inhibitDuringCooling", "bool", title: "Inhibit Dehumidification when Thermostat is Cooling?", required: false, defaultValue: false
            input "inhibitAfterCooling", "bool", title: "Inhibit Dehumidification after a Thermostat Cooling Cycle?", required: false, defaultValue: true, submitOnChange: true
            if (inhibitAfterCooling) {
                input "inhibitTimeout", "number", title: "Inhibit Dehumidification after Cooling Cycle Timeout (mins)", required: false, range: "0..30", defaultValue: 10
            } else {
                inhibitTimeout = 0
            }
        }       
        section("Associated Devices Settings")
        {
            input "powerMeter", "capability.powerMeter", title: "Dehumidifier Power Meter:", required: true, multiple: false
            input "humFeedbackPwrLimit", "number", title: "Dehumidify Control Feedback Power Threshold (W)", required: true, range: "200..500", defaultValue: 250
            input "humFeedbackSwitch", "capability.switch", title: "Dehumidify Control Feedback Indicator - Switch:", required: true, multiple: false
            input "fanFeedbackPwrLimit", "number", title: "Fan Control Feedback Power Threshold (W)", required: false, range: "0..150", defaultValue: 50
            input "fanFeedbackSwitch", "capability.switch", title: "Fan Control Feedback Indicator - Switch:", required: false, multiple: false
            input "tstat", "capability.thermostat", title: "Associated Thermostat:", required: true, multiple: false
            input "tstatFanControl", "bool", title: "Force Thermostat Fan Mode (On/Circ) Based On Dehumidifying State", required: true, defaultValue: true, submitOnChange: true
            if (tstatFanControl) {
                input "tstatFanResetDelay", "number", title: "Thermostat Fan Mode Return to Circ Delay (mins)", required: false, range: "1..15", defaultValue: 3
            } else {
                tstatFanResetDelay = 0
            }
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
				,defaultValue : "0"
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
    unschedule()
	initialize()
}

def initialize()
{
    infolog "+++ Initializing +++"

    // Not sure what to use auto mode for yet
    state.autoMode = true
    
    // Initialize the controller un-inhibited; later logic will determine inhibited state based on thermostat operating state 
    state.inhibited = false
    
    // offPermissive is used to prevent the controller transitioning to off prior to minOnTime. Prevents short-cycling of the dehumidifier.
    // While offPermissive is true, the controller is permitted to turn off.
    state.offPermissive = true
    
    // state.starting is used to prevent bouncing of the controller outputs during the startup i.e. turn on sequence.
    // state.stopping is used to prevent bouncing of the controller outputs during the shutdwon i.e. turn off sequence.
    state.starting = false
    state.stopping = false
    
    // Need to initialize the controller state - use the humidControlSwitch state
    state.co = (humControlSwitch.currentValue("switch") == "on") ? true : false

    subscribe(humControlSwitch, "switch", humSwitchHandler)   
    
    if (fanControlSwitch != null) {
        subscribe(fanControlSwitch, "switch", fanSwitchHandler)
    }
    
    state.pv  = tempSensor.currentValue("temperature").toFloat()
    state.pvStr = String.format("%.1f", state.pv) + "°F"
    subscribe(tempSensor, "temperature", dewpointHandler)
    subscribe(loopSpt, "temperature", sptHandler)
    
    state.tstatMode = tstat.currentValue("thermostatMode")
    subscribe(tstat, "thermostatMode", tstatModeHandler)
    
    state.tstatFanMode = tstat.currentValue("thermostatFanMode")
    state.prevTstatFanMode = state.tstatFanMode
    subscribe(tstat, "thermostatFanMode", tstatFanModeHandler)

    state.tstatState = tstat.currentValue("thermostatOperatingState")
    state.prevTstatState = state.tstatState
    subscribe(tstat, "thermostatOperatingState", tstatStateHandler)
    
    // Initialize the counters for how long the thermostat has been in cooling state since last transiton from idle. Preserve
    // the timestamps etc. if this is an updated() vs created() invocation. Use this as the basis for the inhibitTimeout calculation
    // that runs every minute.
    if (state.coolingStartTime == null) {
        debuglog "initialize: thermostat state timestamps are null - initializing time-in-state variables"
        state.coolingStartTime = now()
        state.coolingStopTime = state.coolingStartTime
        state.secondsInCooling = 0
        state.secondsInIdle = 0
    } else {
        debuglog "initialize: thermostat state timestamps exist - time-in-state variables unchanged"
    }
    calcTstatStateTimers()
    runEvery1Minute("calcTstatStateTimers")
    
    state.power = powerMeter.currentValue("power")    
    subscribe(powerMeter, "power", powerMeterHandler)

    calcEquipmentStates()
    calcSetpoints()
    execControlLoop()
    
    state.label = customLabel
}

def dewpointHandler(evt)
{
    infolog "dewpointHandler: value changed: ${evt.value}"

    state.pv = tempSensor.currentValue("temperature").toFloat()
    state.pvStr = String.format("%.1f", state.pv) + "°F"
    
    calcTstatStateTimers()
    execControlLoop()
}

def sptHandler(evt)
{
    infolog "sptHandler: setpoint changed: ${evt.value}"

    calcSetpoints()
    execControlLoop()
}

def powerMeterHandler(evt)
{
    infolog "powerMeterHandler: Power changed: ${evt.value}"
    
    state.power = evt.value
    calcEquipmentStates()
}

def fanSwitchHandler(evt) 
{
    infolog "fanSwitchHandler: Switch changed: ${evt.value}"
    
	switch(evt.value)
	{
		case "on":
            state.fanCo = true
			break
        case "off":
            state.fanCo = false
			break
    }
}

def humSwitchHandler(evt) 
{
    infolog "humSwitchHandler: Switch changed: ${evt.value}"
    
	switch(evt.value)
	{
		case "on":
            state.humCo = true
			break
        case "off":
            state.humCo = false
			break
    }
}

def tstatModeHandler(evt)   
{
    infolog "tstatModeHandler: Thermostat mode changed: ${evt.value}"
    state.tstatMode = evt.value
}

def tstatStateHandler(evt) {   
    infolog "tstatStateHandler: Thermostat operating state changed: ${evt.value}"
    
    state.tstatState = evt.value
        
    if (state.tstatState == "cooling") {
        if (state.prevTstatState == "idle") {
            // Idle->Cooling transition, capture the start time
            state.coolingStartTime = now()
            state.prevTstatState = state.tstatState
        }
    }
    
    if (state.tstatState == "idle") {
        if (state.prevTstatState == "cooling") {
            // Cooling->Idle transition, capture the end time
            state.coolingStopTime = now()
            state.prevTstatState = state.tstatState
        }
    }
}

def tstatFanModeHandler(evt)
{
    infolog "tstatFanModeHandler: Thermostat fan mode changed: new mode ${evt.value}, previous mode ${state.prevTstatFanMode}"
    state.prevTstatFanMode = state.tstatFanMode
    state.tstatFanMode = evt.value
}

def calcSetpoints() 
{
    infolog "+++ calcSetpoints +++"
    debuglog "calcSetpoints: controller output = ${state.co}"
    
    spt = loopSpt.currentValue("temperature").toFloat()
    state.loopSptLow = (spt - (loopDband / 2.0))
    state.loopSptHigh = (spt + (loopDband / 2.0))
    infolog "calcSetpoints: setpoint = ${spt}, loopDband = ${loopDband}, loopSptLow = ${state.loopSptLow}, loopSptHigh = ${state.loopSptHigh}"
}

def calcEquipmentStates() 
{
    infolog "+++ calcEquipmentStates +++"
    debuglog "calcEquipmentStates: controller output = ${state.co}"
    
    pwr = state.power.toDouble()
    debuglog "calcEquipmentStates: pwr = ${pwr}"
    
    state.humFeedback = (pwr > humFeedbackPwrLimit) ? true : false
    debuglog "calcEquipmentStates: humFeedbackSwitch = ${state.humFeedback}"
    state.humFeedback ? humFeedbackSwitch.on() : humFeedbackSwitch.off()

    if (fanFeedbackSwitch != null) {
        state.fanFeedback = (pwr > fanFeedbackPwrLimit) ? true : false
        debuglog "calcEquipmentStates: fanFeedbackSwitch = ${state.fanFeedback}"
        state.fanFeedback ? fanFeedbackSwitch.on() : fanFeedbackSwitch.off()
    }
}

def calcTstatStateTimers()
{
    infolog "+++ calcTstatStateTimers +++"
    debuglog "calcTstatStateTimers: controller output = ${state.co}"
    
    currentTime = now()
    if (state.tstatState == "cooling") {
        state.secondsInCooling = (int) ((currentTime - state.coolingStartTime) / 1000)
        minsCooling = (state.secondsInCooling / 60).toDouble().round(1)
        debuglog "calcTstatStateTimers: tstatState = cooling for ${minsCooling} min"
    }
    
    if (state.tstatState == "idle") {
        state.secondsInIdle = (int) ((currentTime - state.coolingStopTime) / 1000)
        minsIdle = (state.secondsInIdle / 60).toDouble().round(1)
        debuglog "calcTstatStateTimers: tstatState = idle for ${minsIdle} min"
    }
    
    // Now determine if the controller dehumidification should be inhibited 1) if cooling, or 2) after cooling until the inhibitTimeout has elapsed
    state.inhibited = false
    
    if (inhibitDuringCooling && (state.tstatState == "cooling")) {
        state.inhibited = true
        debuglog "calcTstatStateTimers: dehumidification inhibited while cooling"
    }
    
    if (inhibitAfterCooling && (state.tstatState == "idle") && (state.secondsInIdle < (60 * inhibitTimeout))) {
        state.inhibited = true
        debuglog "calcTstatStateTimers: dehumidification inhibited after cooling cycle, idle time ${minsIdle} < ${inhibitTimeout} min"
    }    
}

def execControlLoop()
{     
    infolog "+++ execControlLoop +++"
    debuglog "execControlLoop: pv = ${state.pv}"

    // Compute excursion states
        
    state.excursionLow = (state.pv < state.loopSptLow)
    state.excursionHigh = (state.pv > state.loopSptHigh)
            
    // Compute new controller state
    if (state.inhibited && state.offPermissive) {
        state.co = false
        debuglog "execControlLoop: pv = ${state.pv}, inhibited = ${state.inhibited}, state.co = ${state.co}, turnOffOutput"
        updateAppLabel("Inhibited - Output Off", "orange")
        turnOffOutput()
    } else if (state.excursionLow && state.offPermissive) {
        debuglog "execControlLoop: pv ${state.pv} < loSpt ${state.loopSptLow} -> excursionLow, offPermissive = ${state.offPermissive}, turnOffOutput"
        state.co = false
        updateAppLabel("Below Low Limit - Output Off", "red", state.pvStr)
        turnOffOutput()
    } else if (state.excursionHigh) {
        debuglog "execControlLoop: pv ${state.pv} > hiSpt ${state.loopSptHigh} -> excursionHigh, turnOnOutput"
        state.co = true
        updateAppLabel("Above High Limit - Output On", "green", state.pvStr)
        turnOnOutput()
    } else {
        debuglog "execControlLoop: loSpt ${state.loopSptLow} < pv ${state.pv} <  hiSpt ${state.loopSptHigh} -> within limits, controller output unchanged (${state.co})"
        state.co ? updateAppLabel("Within Limits - Output On", "green", state.pvStr) : updateAppLabel("Within Limits - Output Off", "red", state.pvStr)
    }
}

def turnOnOutput()
{
    infolog "+++ turnOnOutput +++"
    debuglog "turnOnOutput: controller output = ${state.co}"
    //
    // Turn on sequence:
    //  1) Start fan (if configured)
    //  2) Wait onDelay seconds
    //  3) Start compressor
    //  4) Set tstat fan mode to on (if configured)
    //  5) Set offPermissive to false 
    //  6) Schedule offPermissive reset
    //

    if (!state.humFeedback && !state.starting) {
        debuglog "turnOnOutput: humFeedback is ${state.humFeedback}, on sequence started, state.starting = true"
        
        // Set the state.starting flag; we will reset this after confirmation of the compressor start command
        state.starting = true
        
        if (fanControlSwitch != null) {
            debuglog "turnOnOutput: onDelay = ${onDelay}, fanControlSwitch = ${fanControlSwitch}, minOnTime = ${minOnTime}"
            fanSwitchOn()
            (onDelay > 0) ? runIn(60 * onDelay, 'humSwitchOn') : humSwitchOn()
        } else {    
            humSwitchOn()
        }
        if (tstatFanControl) {
            debuglog "turnOnOutput: tstatFanControl = ${tstatFanControl}, setting tstat fan to on"
            tstatFanOn()
        }
        if (minOnTime > 0) {
            debuglog "turnOnOutput: offPermissive set to false, will reset in ${minOnTime} min"
            state.offPermissive = false
            runIn(60 * minOnTime, 'resetOffPermissive')
        }
    } else {
        if (state.starting) {
            debuglog "turnOnOutput: humFeedback is ${state.humFeedback}, state.starting=true"
        } else {
            debuglog "turnOnOutput: humFeedback is ${state.humFeedback}, output already on"
        }
    }

}

def turnOffOutput()
{
    infolog "+++ turnOffOutput +++"
    debuglog "turnOffOutput: controller output = ${state.co}"
    //
    // Turn off sequence:
    //  1) Wait onDelay seconds
    //  2) Stop compressor
    //  3) Stop fan (if configured)
    //  4) Set tstat fan mode to circ (if configured), after tstatFanResetDelay minutes
    //

    if (state.humFeedback && !state.stopping) {
        debuglog "turnOffOutput: humFeedback is ${state.humFeedback}, off sequence started, state.stopping = true, off delay = ${onDelay} min"
        
        // Set the state.stopping flag; we will reset this after confirmation of the compressor stop command
        state.stopping = true
        
        (onDelay > 0) ? runIn(60 * onDelay, 'humSwitchOff') : humSwitchOff()
        if (fanControlSwitch != null) {
            debuglog "turnOffOutput: fanControlSwitch = ${fanControlSwitch}"
            fanSwitchOff()
        }
        if (tstatFanControl) {
            debuglog "turnOffOutput: tstatFanControl = ${tstatFanControl}, setting tstat fan to circ after ${tstatFanResetDelay} min"
            (tstatFanResetDelay > 0) ? runIn(60 * tstatFanResetDelay, 'tstatFanCirc') : tstatFanCirc()
        }
    } else {
        if (state.stopping) {
            debuglog "turnOffOutput: humFeedback is ${state.humFeedback}, state.stopping=true"
        } else {
            debuglog "turnOffOutput: humFeedback is ${state.humFeedback}, output already off"
        }
    }

}

def humSwitchOn() {
    debuglog "humControlSwitch: on, state.starting=false"
    humControlSwitch.on()
    state.starting = false
}

def humSwitchOff() {
    debuglog "humControlSwitch: off, state.stopping=false"
    humControlSwitch.off()
    state.stopping = false
}

def fanSwitchOn() {
    debuglog "fanControlSwitch: on"
    fanControlSwitch.on()
}

def fanSwitchOff() {
    debuglog "fanControlSwitch: off"
    fanControlSwitch.off()
}

def tstatFanOn() {
    debuglog "tstatFanMode: on"
    tstat.fanOn()
}

def tstatFanCirc() {
    debuglog "tstatFanMode: circulate"
    tstat.fanCirculate()
}

def resetOffPermissive() {
    debuglog "+++ resetOffPermissive +++"
    debuglog "resetOffPermissive: offPermissive set to true, controller can now transition to off state"
    state.offPermissive = true
    execControlLoop()
}

//
// Utility functions
//
def updateAppLabel(textStr, textColor, textPrefix = '') {
    def str = """<span style='color:$textColor'> ($textPrefix $textStr)</span>"""
    app.updateLabel(customLabel + str)
}

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
