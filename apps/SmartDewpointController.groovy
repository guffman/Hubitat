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
*    2021-07-04  Marc Chevis    Removed thermostat fan control modes. Logic (if needed) better implemented outside of the app.
*    2021-07-07  Marc Chevis    Added high humidity override feature.
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
        section("<b>General</b>") 
        {
		input (name: "customLabel", type: "text", title: "Enter a name for the app (optional)", required: false, defaultValue: "Smart Dewpoint Controller")
		}
	    section("<b>Gap Controller Input/Output Devices</b>")
		{
            input "loopSpt", "capability.temperatureMeasurement", title: "Dewpoint Setpoint:", required: true, multiple: false
			input "tempSensor", "capability.temperatureMeasurement", title: "Dewpoint Sensor:", required: true, multiple: false
			input "humControlSwitch", "capability.switch", title: "Dehumidify Control Output - Switch or Outlet:", required: true, multiple: false
            input "humFanControl", "bool", title: "Control Dehumidifer Fan?", required: false, defaultValue: false, submitOnChange: true
            if (humFanControl) {
                input "fanControlSwitch", "capability.switch", title: "Fan Control Output - Switch or Outlet:", required: true, multiple: false
                input "onDelay", "number", title: "Dehumidify Control Output On Delay after Fan Start (sec)", required: true, range: "0..60", defaultValue: 15
            } else {
                onDelay = 0
            }
            input "humOverride", "bool", title: "Override Dewpoint Control on High Humidity?", required: false, defaultValue: false, submitOnChange: true
            if (humOverride) {
                input "humSpt", "number", title: "Humidity Override Setpoint (%RH):", required: true, range: "50..65", defaultValue: 60
			    input "humSensor", "capability.relativeHumidityMeasurement", title: "Humidity Sensor:", required: true, multiple: false
            }
		}
		section("<b>Gap Controller Settings</b>")
		{
            input "loopDband", "decimal", title: "Deadband (°F)", required: true, range: "0.5..3.0", defaultValue: 1.0
            input "minOnTime", "number", title: "Dehumidify Control Output Minimum On Time (mins)", required: true, range: "5..30", defaultValue: 5
        }
        section("<b>Gap Controller Override Settings</b>")
        {
            input "inhibitDuringCooling", "bool", title: "Inhibit Dehumidify Control during a Thermostat Cooling Cycle?", required: false, defaultValue: false
            if (inhibitDuringCooling) {
                input "inhibitDuringCoolingDelay", "number", title: "When Cooling Cycle starts, delay inhibit for (mins):", required: false, range: "0..30", defaultValue: 5
            } else {
                inhibitDuringCoolingDelay = 0
            }
            input "inhibitAfterCooling", "bool", title: "Inhibit Dehumidification after a Thermostat Cooling Cycle?", required: false, defaultValue: true, submitOnChange: true
            if (inhibitAfterCooling) {
                input "inhibitAfterCoolingDelay", "number", title: "When Cooling Cycle ends, inhibit Dehumidification for (mins):", required: false, range: "0..30", defaultValue: 10
            } else {
                inhibitAfterCoolingDelay = 0
            }
        }       
        section("<b>Associated Devices Settings</b>")
        {
            input "powerMeter", "capability.powerMeter", title: "Dehumidifier Power Meter:", required: true, multiple: false
            input "humFeedbackPwrLimit", "number", title: "Dehumidify Control Feedback Power Threshold (W)", required: true, range: "200..500", defaultValue: 250
            input "humFeedbackSwitch", "capability.switch", title: "Dehumidify Control Feedback Indicator - Switch:", required: true, multiple: false
            input "fanFeedbackPwrLimit", "number", title: "Fan Control Feedback Power Threshold (W)", required: false, range: "0..150", defaultValue: 50
            input "fanFeedbackSwitch", "capability.switch", title: "Fan Control Feedback Indicator - Switch:", required: false, multiple: false
            input "tstat", "capability.thermostat", title: "Associated Thermostat:", required: true, multiple: false
		}
		section("<b>Logging</b>")
		{                       
			input("logLevel", "enum", title: "Logging level", multiple: false, required: true, options: [["0":"None"],["1":"Info"],["2":"Debug"]], defaultValue: "0")  
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
    
    // minOnTimePassed is used to prevent the controller transitioning to off prior to minOnTime. Prevents short-cycling of the dehumidifier.
    // While minOnTimePassed is true, the controller is permitted to turn off.
    state.minOnTimePassed = true
    
    // state.starting is used to prevent bouncing of the controller outputs during the startup i.e. turn on sequence.
    // state.stopping is used to prevent bouncing of the controller outputs during the shutdown i.e. turn off sequence.
    state.starting = false
    state.stopping = false
    
    // Need to initialize the controller state - use the humidControlSwitch state
    state.co = (humControlSwitch.currentValue("switch") == "on") ? true : false

    subscribe(humControlSwitch, "switch", humSwitchHandler)   
    
    if (fanControlSwitch != null) {
        subscribe(fanControlSwitch, "switch", fanSwitchHandler)
    }
    
    if (humOverride) {
        subscribe(humSensor, "humidity", humHandler)
        state.override = (humSpt - humSensor.currentValue("humidity") > 0) ? false : true
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
        debuglog "initialize: thermostat state timestamps already exist - time-in-state variables unchanged"
    }
    calcTstatStateTimers()
    schedule("0 */1 * ? * *", 'calcTstatStateTimers')
    
    state.power = powerMeter.currentValue("power")    
    subscribe(powerMeter, "power", powerMeterHandler)
    calcEquipmentStates(state.power)
    
    calcSetpoints()
    execControlLoop()
    
    state.label = customLabel
}

def dewpointHandler(evt)
{
    infolog "+++ dewpointHandler: value changed: ${evt.value}"

    state.pv = tempSensor.currentValue("temperature").toFloat()
    state.pvStr = String.format("%.1f", state.pv) + "°F"
    
    calcTstatStateTimers()
    execControlLoop()
}

def sptHandler(evt)
{
    infolog "+++ sptHandler: setpoint changed: ${evt.value}"

    calcSetpoints()
    calcTstatStateTimers()
    execControlLoop()
}

def powerMeterHandler(evt)
{
    infolog "+++ powerMeterHandler: Power changed: ${evt.value}"
    
    state.power = evt.value
    calcEquipmentStates(evt.value)
}

def humHandler(evt)
{    
    // Test for override condition, apply 2% hysteresis to prevent flapping only following override state transition
    humPv = evt.value.toDouble()
    if (state.override) {
        ovr = (humSpt - humPv < 2.0) ? true : false
    } else {
        ovr = (humSpt - humPv >= 0) ? false : true
    }
    
    infolog "+++ humHandler: value changed: ${evt.value}, high humidity override = ${ovr}"
    
    // Only execute the loop if in override state or if just made a transition to the override state
    if (state.override && ovr) {    
        calcTstatStateTimers()
        execControlLoop()
    } else if (state.override != ovr) {
        state.override = ovr
        calcTstatStateTimers()
        execControlLoop()
    }
}

def fanSwitchHandler(evt) 
{
    infolog "+++ fanSwitchHandler: Switch changed: ${evt.value}"
    
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
    infolog "+++ humSwitchHandler: Switch changed: ${evt.value}"
    
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
    infolog "+++ tstatModeHandler: Thermostat mode changed: ${evt.value}"
    state.tstatMode = evt.value
}

def tstatStateHandler(evt) {   
    infolog "+++ tstatStateHandler: Thermostat operating state changed: ${evt.value}"
    
    state.tstatState = evt.value
        
    if ((state.tstatState == "cooling") && (state.prevTstatState == "idle")) {
        // Idle->Cooling transition, capture the start time, schedule the inhibited flag set
        state.coolingStartTime = now()
        state.secondsInCooling = 0
        state.prevTstatState = state.tstatState
        if (inhibitDuringCooling) {
            state.inhibited = false
            runIn(inhibitDuringCoolingDelay * 60, 'setInhibit')
        }
    }
    
    if ((state.tstatState == "idle") && (state.prevTstatState == "cooling")) {
        // Cooling->Idle transition, capture the end time, schedule the inhibited flag clear
        state.coolingStopTime = now()
        state.secondsInIdle = 0
        state.prevTstatState = state.tstatState
        if (inhibitAfterCooling) {
            state.inhibited = true
            runIn(inhibitAfterCoolingDelay * 60, 'clearInhibit')
        }
    }
    
    calcTstatStateTimers()
    execControlLoop()
}

def tstatFanModeHandler(evt)
{
    infolog "+++ tstatFanModeHandler: Thermostat fan mode changed: new mode ${evt.value}, previous mode ${state.prevTstatFanMode}"
    state.prevTstatFanMode = state.tstatFanMode
    state.tstatFanMode = evt.value
}

def calcSetpoints() 
{    
    spt = loopSpt.currentValue("temperature").toFloat()
    state.loopSptLow = (spt - (loopDband / 2.0))
    state.loopSptHigh = (spt + (loopDband / 2.0))
    infolog "+++ calcSetpoints: setpoint = ${spt}, loopDband = ${loopDband}, loopSptLow = ${state.loopSptLow}, loopSptHigh = ${state.loopSptHigh}"
}

def calcEquipmentStates(power) 
{
    debuglog "calcEquipmentStates: controller output = ${state.co}"
    
    pwr = power.toDouble()
    infostr = "+++ calcEquipmentStates: pwr = ${pwr}"
    
    state.humFeedback = (pwr > humFeedbackPwrLimit) ? "on" : "off"
    humFeedbackNow = humFeedbackSwitch.currentValue("switch")
    infostr += ", humFeedback computed=${state.humFeedback}, current=${humFeedbackNow}"
    
    if (humFeedbackNow != state.humFeedback) {
        (state.humFeedback == "on") ? humFeedbackSwitch.on() : humFeedbackSwitch.off()
        infostr += ", sending ${state.humFeedback} command"
    }
    
    if (fanFeedbackSwitch != null) {
        
        state.fanFeedback = (pwr > fanFeedbackPwrLimit) ? "on" : "off"
        fanFeedbackNow = fanFeedbackSwitch.currentValue("switch")
        infostr += ", fanFeedback computed=${state.fanFeedback}, current=${fanFeedbackNow}"
        
        if (fanFeedbackNow != state.fanFeedback) {
            (state.fanFeedback == "on") ? fanFeedbackSwitch.on() : fanFeedbackSwitch.off()
            infostr += ", sending ${state.fanFeedback} command"
        }
    }
    infolog infostr
}

def setInhibit() {
    state.inhibited = true
}  
    
def clearInhibit() {
    state.inhibited = false
}    

def calcTstatStateTimers()
{
    debuglog "calcTstatStateTimers: controller output = ${state.co}"
    infostr = "+++ calcTstatStateTimers: "
    
    currentTime = now()
    if (state.tstatState == "cooling") {
        state.secondsInCooling = (int) ((currentTime - state.coolingStartTime) / 1000)
        minsCooling = (state.secondsInCooling / 60).toDouble().round(1)
        infostr += "tstatState = cooling for ${minsCooling} min"
    }
    
    if (state.tstatState == "idle") {
        state.secondsInIdle = (int) ((currentTime - state.coolingStopTime) / 1000)
        minsIdle = (state.secondsInIdle / 60).toDouble().round(1)
        infostr += "tstatState = idle for ${minsIdle} min"
    }
    infolog infostr
}

def execControlLoop()
{     
    infostr = "+++ execControlLoop: "
    debuglog "execControlLoop: pv = ${state.pv}"

    // Compute excursion states
        
    state.excursionLow = (state.pv < state.loopSptLow)
    state.excursionHigh = (state.pv > state.loopSptHigh)
            
    // Compute new controller state. Check the inhibit flags. If high humidity override condition, turn on output, or prevent off if already on.
    if (state.inhibited && state.minOnTimePassed) {
        state.co = false
        debuglog "execControlLoop: pv = ${state.pv}, inhibited = ${state.inhibited}, state.co = ${state.co}, turnOffOutput"
        infostr += "turnOffOutput"
        updateAppLabel("Inhibited - Output Off", "orange")
        turnOffOutput()
    } else if (state.excursionLow && state.minOnTimePassed && !state.override) {
        debuglog "execControlLoop: pv ${state.pv} < loSpt ${state.loopSptLow} -> excursionLow, minOnTimePassed = ${state.minOnTimePassed}, turnOffOutput"
        infostr += "turnOffOutput"
        state.co = false
        updateAppLabel("Below Low Limit - Output Off", "red", state.pvStr)
        turnOffOutput()
    } else if (state.override) {
        debuglog "execControlLoop: high humidity override - turnOnOutput"
        infostr += "turnOnOutput"
        state.co = true
        updateAppLabel("High Humidity Override - Output On", "orange")
        turnOnOutput()
    } else if (state.excursionHigh) {
        debuglog "execControlLoop: pv ${state.pv} > hiSpt ${state.loopSptHigh} -> excursionHigh, turnOnOutput"
        infostr += "turnOnOutput"
        state.co = true
        updateAppLabel("Above High Limit - Output On", "green", state.pvStr)
        turnOnOutput()
    } else {
        debuglog "execControlLoop: loSpt ${state.loopSptLow} < pv ${state.pv} <  hiSpt ${state.loopSptHigh} -> within limits, controller output unchanged (${state.co})"
        infostr += "no output change"
        state.co ? updateAppLabel("Within Limits - Output On", "green", state.pvStr) : updateAppLabel("Within Limits - Output Off", "red", state.pvStr)
    }
    infolog infostr
}

def turnOnOutput()
{
    infostr = "controller output = ${state.co}"
    infolog "+++ turnOnOutput: ${infostr}"
    
    //
    // Turn on sequence:
    //  1) Start fan (if configured)
    //  2) Wait onDelay seconds
    //  3) Start compressor
    //  4) Set minOnTimePassed to false 
    //  5) Schedule setMinOnTimePassed 
    //

    if ((state.humFeedback == "off") && !state.starting) {
        debuglog "turnOnOutput: humFeedback = ${state.humFeedback}, state.starting = true, on sequence started"
        
        // Set the state.starting flag; we will reset this after confirmation of the dehumidifying on command
        state.starting = true
        
        if (fanControlSwitch != null) {
            debuglog "turnOnOutput: onDelay = ${onDelay}, fanControlSwitch = ${fanControlSwitch}, minOnTime = ${minOnTime}"
            fanSwitchOn()
            (onDelay > 0) ? runIn(onDelay, 'humSwitchOn') : humSwitchOn()
        } else {    
            humSwitchOn()
        }
        if (minOnTime > 0) {
            debuglog "turnOnOutput: minOnTimePassed set to false, will toggle in ${minOnTime} min"
            state.minOnTimePassed = false
            runIn(60 * minOnTime, 'setMinOnTimePassed')
        } else {
            state.minOnTimePassed = true
        }
    } else {
        if (state.starting) {
            debuglog "turnOnOutput: humFeedback = ${state.humFeedback}, state.starting=true"
        } else {
            debuglog "turnOnOutput: humFeedback = ${state.humFeedback}, output already on"
        }
    }
}

def turnOffOutput()
{
    infostr = "controller output = ${state.co}"
    infolog "+++ turnOffOutput: ${infostr}"
    //
    // Turn off sequence:
    //  1) Stop compressor
    //  2) Stop fan (if configured)
    //
    // Don't apply any delays, as this will re-introduce moisture on the dehumidifier coils if we keep running the fan.
    //

    if ((state.humFeedback == "on") && !state.stopping) {
        debuglog "turnOffOutput: humFeedback = ${state.humFeedback}, state.stopping = true, off sequence started"
        
        // Set the state.stopping flag; we will reset this after confirmation of the compressor stop command
        state.stopping = true 
        humSwitchOff()
        if (fanControlSwitch != null) {
            debuglog "turnOffOutput: fanControlSwitch = ${fanControlSwitch}"
            fanSwitchOff()
        }
    } else {
        if (state.stopping) {
            debuglog "turnOffOutput: humFeedback = ${state.humFeedback}, state.stopping=true"
        } else {
            debuglog "turnOffOutput: humFeedback = ${state.humFeedback}, output already off"
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

def tstatFanAuto() {
    debuglog "tstatFanMode: auto"
    tstat.fanAuto()
}

def setMinOnTimePassed() {
    debuglog "setMinOnTimePassed: minOnTimePassed set to true, controller can now transition to off state"
    state.minOnTimePassed = true
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
