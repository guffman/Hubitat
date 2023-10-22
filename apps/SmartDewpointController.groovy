/*
*  Smart Dewpoint Controller
*
*  Adjusts a switch/outlet based on a target high / low dewpoint 
*    -Use a standard differential gap algorithm for the control algorithm
*    -Provide for dewpoint control override based on high humidity
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
*    2021-10-12  Marc Chevis    Added additional info log messages.
*    2021-10-27  Marc Chevis    Added logic to reduce short run times of the dehumidifer (needs to be running for ~10 mins to dehumidify efficiently).
*    2022-05-05  Marc Chevis    Revised high humidity override logic, inhibit after cooling cycle changed to allow dehumidifer to continue running if in on state.
*    2022-05-27  Marc Chevis    Added event handler for high humidity override setpoint change.
*    2023-10-22  Marc Chevis    Fixed inhibit logic - thermostat start cooling was not inhibiting the dehumidifer control output.
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
                input "humSpt", "capability.relativeHumidityMeasurement", title: "Humidity Override Setpoint:", required: true, multiple: false
			    input "humSensor", "capability.relativeHumidityMeasurement", title: "Humidity Sensor:", required: true, multiple: false
            }
		}
		section("<b>Gap Controller Settings</b>")
		{
            input "loopDband", "decimal", title: "Deadband (°F)", required: true, range: "0.5..3.0", defaultValue: 1.0
            input "minOnTime", "number", title: "Dehumidify Control Output Minimum On Time (mins)", required: true, range: "5..30", defaultValue: 10
        }
        section("<b>Gap Controller Override Settings</b>")
        {
            input "inhibitDuringCooling", "bool", title: "Inhibit Dehumidify Control Output during a Thermostat Cooling Cycle?", required: false, defaultValue: false, submitOnChange: true
            if (inhibitDuringCooling) {
                input "inhibitDuringCoolingDelay", "number", title: "When Cooling Cycle starts, if Dehumidifier running, delay inhibit for (mins):", required: true, range: "0..30", defaultValue: 5
            } else {
                inhibitDuringCoolingDelay = 0
            }
            input "inhibitAfterCooling", "bool", title: "Inhibit Dehumidify Control Output after a Thermostat Cooling Cycle?", required: false, defaultValue: true, submitOnChange: true
            if (inhibitAfterCooling) {
                input "inhibitAfterCoolingDelay", "number", title: "When Cooling Cycle ends, if Dehumidifer idle, inhibit running for (mins):", required: true, range: "0..30", defaultValue: 10
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
            input "inhibitFeedbackSwitch", "capability.switch", title: "Controller Inhibited Feedback Indicator - Switch:", required: false, multiple: false
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
    
    // Initialize the controller override disabled; later logic will determine override state based on high humidity
    state.override = false
    
    // minOnTimePassed is used to prevent the controller transitioning to off prior to minOnTime. Prevents short-cycling of the dehumidifier.
    // While minOnTimePassed is true, the controller is permitted to turn off.
    state.minOnTimePassed = true
    
    // state.starting is used to prevent bouncing of the controller outputs during the startup i.e. turn on sequence.
    // state.stopping is used to prevent bouncing of the controller outputs during the shutdown i.e. turn off sequence.
    state.starting = false
    state.stopping = false
    
    // Need to initialize the controller state - use the humControlSwitch state
    state.co = (humControlSwitch.currentValue("switch") == "on") ? true : false

    subscribe(humControlSwitch, "switch", humSwitchHandler)   
    subscribe(humFeedbackSwitch, "switch", humFeedbackSwitchHandler)
    
    if (fanControlSwitch != null) {
        subscribe(fanControlSwitch, "switch", fanSwitchHandler)
    }
    
    if (humOverride) { 
        subscribe(humSensor, "humidity", humHandler)
        subscribe(humSpt, "humidity", humSptHandler)
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
        debuglog "+++ initialize: thermostat state timestamps already exist - time-in-state variables unchanged"
    }
    calcTstatStateTimers()
    //schedule("0 */1 * ? * *", 'calcTstatStateTimers')
    
    state.power = powerMeter.currentValue("power")    
    subscribe(powerMeter, "power", powerMeterHandler)
    calcEquipmentStates()
    
    calcSetpoints()
    execControlLoop()
    
    state.label = customLabel
}

def dewpointHandler(evt)
{
    infolog "+++ dewpointHandler: value changed: ${evt.value}, sp high = ${state.loopSptHigh}, sp low = ${state.loopSptLow}, override = ${state.override}"

    state.pv = tempSensor.currentValue("temperature").toFloat()
    state.pvStr = String.format("%.1f", state.pv) + "°F"
    
    calcTstatStateTimers()
    calcEquipmentStates()
    execControlLoop()
}

def sptHandler(evt)
{
    infolog "+++ sptHandler: setpoint changed: ${evt.value}"

    calcSetpoints()
    calcTstatStateTimers()
    calcEquipmentStates()
    execControlLoop()
}

def humSptHandler(evt)
{
    infolog "+++ humSptHandler: setpoint changed: ${evt.value}"
    
    calcSetpoints()
    calcTstatStateTimers()
    calcEquipmentStates()
    execControlLoop()
}

def powerMeterHandler(evt)
{
    debuglog "+++ powerMeterHandler: Power changed: ${evt.value}"
    
    state.power = evt.value
    calcEquipmentStates()
}

def humHandler(evt)
{    
    humPv = evt.value.toDouble()
    
    // Test for override condition
    if (state.override) {
        ovr = (humPv < state.humSptLow)  ? false : true
    } else {
        ovr = (humPv > state.humSptHigh) ? true : false
    }
    
    infolog "+++ humHandler: value changed: ${evt.value}, spt high = ${state.humSptHigh}, spt low = ${state.humSptLow}, state.override = ${state.override}, ovr = ${ovr}"
    
    // Only execute the loop if in override state or if just made a transition
    if (state.override && ovr) {    
        calcTstatStateTimers()
        execControlLoop()
    } else if (state.override != ovr) {
        state.override = ovr
        infolog "+++ humHandler: override transition, state.override= ${state.override}"
        calcTstatStateTimers()
        execControlLoop()
        
    }
}

def fanSwitchHandler(evt) 
{
    debuglog "+++ fanSwitchHandler: Switch changed: ${evt.value}"
    
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
    debuglog "+++ humSwitchHandler: Switch changed: ${evt.value}"
    
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

def humFeedbackSwitchHandler(evt) 
{
    def infostr = "+++ humFeedbackSwitchHandler: Switch changed: ${evt.value}"
    
	switch(evt.value)
	{
		case "on":
            state.starting = false
            infostr += ", state.starting=false"
			break
        case "off":
            state.stopping = false
            infostr += ", state.stopping=false"
			break
    }
    infolog infostr
}

def tstatModeHandler(evt)   
{
    infolog "+++ tstatModeHandler: Thermostat mode changed: ${evt.value}"
    state.tstatMode = evt.value
}

def tstatStateHandler(evt) {   
    infolog "+++ tstatStateHandler: Thermostat state changed: ${evt.value}, state.prevTstatState: ${state.prevTstatState}"
    
    state.tstatState = evt.value

    if ((state.tstatState == "cooling") && (state.prevTstatState != "cooling")) {
        // Idle->Cooling transition, capture the start time, schedule the inhibited flag set, only if dehumidifier running. Inhibit if dehumidifier not running.
        state.coolingStartTime = now()
        state.secondsInCooling = 0
        state.prevTstatState = "cooling"
        if (inhibitDuringCooling && !state.co) {
            infolog "+++ tstatStateHandler: Inhibit during cooling, state.co: ${state.co}, calling setInhibit"
            setInhibit()
        } else if (inhibitDuringCooling && state.co) {
            infolog "+++ tstatStateHandler: Inhibit during cooling, state.co: ${state.co}, calling clearInhibit, will setInhibit after ${inhibitDuringCoolingDelay} min"
            clearInhibit()
            inhibitDuringCoolingDelay ? runIn(inhibitDuringCoolingDelay * 60, 'setInhibit') : setInhibit()
        }
    }
    
    if ((state.tstatState != "cooling") && (state.prevTstatState == "cooling")) {
        // Cooling->Idle transition, capture the end time, schedule the inhibited flag clear, only if dehumidifer not running
        state.coolingStopTime = now()
        state.secondsInIdle = 0
        state.prevTstatState = state.tstatState
        if (inhibitAfterCooling && !state.co) {
            infolog "+++ tstatStateHandler: Inhibit after cooling, state.co: ${state.co}, calling setInhibit, will clearInhibit after ${inhibitAfterCoolingDelay} min"
            setInhibit()
            inhibitAfterCoolingDelay ? runIn(inhibitAfterCoolingDelay * 60, 'clearInhibit') : clearInhibit()
        }
    }
    
    calcTstatStateTimers()
    execControlLoop()
}

def setInhibit() {
    state.inhibited = true
    infolog "+++ setInhibit: state.inhibited=true"
    if (inhibitFeedbackSwitch != null) inhibitFeedbackSwitch.on()
    execControlLoop()
}  
    
def clearInhibit() {
    state.inhibited = false
    infolog "+++ clearInhibit: state.inhibited=false"
    if (inhibitFeedbackSwitch != null) inhibitFeedbackSwitch.off()
    execControlLoop()
}    

def tstatFanModeHandler(evt)
{
    debuglog "+++ tstatFanModeHandler: Thermostat fan mode changed: new mode ${evt.value}, previous mode ${state.prevTstatFanMode}"
    state.prevTstatFanMode = state.tstatFanMode
    state.tstatFanMode = evt.value
}

def calcSetpoints() 
{    
    spt = loopSpt.currentValue("temperature").toFloat()
    state.loopSptHigh = spt
    state.loopSptLow = spt - loopDband
    debuglog "+++ calcSetpoints: dewpoint setpoint = ${spt}, loopDband = ${loopDband}, loopSptLow = ${state.loopSptLow}, loopSptHigh = ${state.loopSptHigh}"
    
    hspt = humSpt.currentValue("humidity").toFloat()
    state.humSptHigh = hspt
    state.humSptLow = hspt - 1.0
    debuglog "+++ calcSetpoints: humidity override setpoint = ${hspt}, loopDband = 1.0, humSptLow = ${state.humSptLow}, humSptHigh = ${state.humSptHigh}"
}

def calcEquipmentStates() 
{
    debuglog "+++ calcEquipmentStates: state.co = ${state.co}"
    
    pwr = powerMeter.currentValue("power").toDouble()  
    def infostr = "+++ calcEquipmentStates: pwr = ${pwr}"
    
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
    debuglog infostr
}

def calcTstatStateTimers()
{
    debuglog "+++ calcTstatStateTimers: state.co = ${state.co}"
    def infostr = "+++ calcTstatStateTimers: "
    
    currentTime = now()
    if (state.tstatState == "cooling") {
        state.secondsInCooling = (int) ((currentTime - state.coolingStartTime) / 1000)
        minsCooling = (state.secondsInCooling / 60).toDouble().round(1)
        infostr += "tstatState = cooling for ${minsCooling} min"
    }
    
    if ((state.tstatState == "idle") || (state.tstatState == "pending cool")) {
        state.secondsInIdle = (int) ((currentTime - state.coolingStopTime) / 1000)
        minsIdle = (state.secondsInIdle / 60).toDouble().round(1)
        infostr += "tstatState = idle for ${minsIdle} min"
    }
    debuglog infostr
}

def execControlLoop()
{     
    def infostr = "+++ execControlLoop:"

    // Compute excursion states
        
    state.excursionLow = (state.pv <= state.loopSptLow)
    state.excursionHigh = (state.pv > state.loopSptHigh)
            
    // Compute new controller state. Check the inhibit flag 1st since it takes precedence over all other states.
    // If high humidity override condition, turn on output, or prevent off if already on.
    // If necessary, force controller output on until minOnTimePassed timer expires.

    if (state.inhibited) {
        state.co = false
        debuglog "${infostr} pv = ${state.pv}, inhibited = ${state.inhibited}, state.co = ${state.co}, turnOffOutput"
        infolog "${infostr} inhibited, turnOffOutput"
        updateAppLabel("Inhibited - Output Off", "red")
        turnOffOutput()
    } else if (state.override) {
        state.co = true
        debuglog "${infostr} pv = ${state.pv}, state.override = ${state.override}, state.co = ${state.co}, turnOnOutput"
        infolog "${infostr} high humidity override, turnOnOutput"
        updateAppLabel("High Humidity Override - Output On", "orange")
        turnOnOutput()
    } else if (state.excursionLow && state.co && !state.minOnTimePassed) {
        debuglog "${infostr} pv ${state.pv} < loSpt ${state.loopSptLow} -> excursionLow, minOnTimePassed = ${state.minOnTimePassed}, controller output unchanged (${state.co})"
        infolog "${infostr} excursionLow, minOnTimePassed = false, no output change"
        updateAppLabel("Minimum On Timer Active - Output On", "green", state.pvStr)
    } else if (state.excursionLow && state.minOnTimePassed) {
        state.co = false
        debuglog "${infostr} pv ${state.pv} < loSpt ${state.loopSptLow} -> excursionLow, minOnTimePassed = ${state.minOnTimePassed}, turnOffOutput"
        infolog "${infostr} excursionLow, minOnTimePassed = true, turnOffOutput"
        updateAppLabel("Below Low Limit - Output Off", "red", state.pvStr)
        turnOffOutput()
    } else if (state.excursionHigh) {
        state.co = true
        debuglog "${infostr} pv ${state.pv} > hiSpt ${state.loopSptHigh} -> excursionHigh, turnOnOutput"
        infolog "${infostr} excursionHigh, turnOnOutput"
        updateAppLabel("Above High Limit - Output On", "green", state.pvStr)
        turnOnOutput()
    } else {
        debuglog "${infostr} loSpt ${state.loopSptLow} < pv ${state.pv} <  hiSpt ${state.loopSptHigh} -> within limits, controller output unchanged (${state.co})"
        infolog "${infostr} within limits, no output change"
        state.co ? updateAppLabel("Within Limits - Output On", "green", state.pvStr) : updateAppLabel("Within Limits - Output Off", "red", state.pvStr)
    }
}

def turnOnOutput()
{
    def infostr = "+++ turnOnOutput: state.co = ${state.co}"
    
    //
    // Turn on sequence:
    //  1) Start fan (if configured)
    //  2) Wait onDelay seconds
    //  3) Start compressor
    //  4) Set minOnTimePassed to false 
    //  5) Schedule setMinOnTimePassed 
    //

    if ((state.humFeedback == "off") && !state.starting) {
        debuglog "+++ turnOnOutput: humFeedback = ${state.humFeedback}, state.starting = true, on sequence started"
        
        // Set the state.starting flag; we will reset this after confirmation of the dehumidifying on command via the feedback switch event
        state.starting = true
        
        if (fanControlSwitch != null) {
            debuglog "+++ turnOnOutput: onDelay = ${onDelay}, fanControlSwitch = ${fanControlSwitch}, minOnTime = ${minOnTime}"
            fanSwitchOn()
            infostr += ", turning on fanControlSwitch, then humControlSwitch after ${onDelay} sec"
            (onDelay > 0) ? runIn(onDelay, 'humSwitchOn') : humSwitchOn()
        } else {    
            humSwitchOn()
            infostr += ", turning on humControlSwitch"
        }
        if (minOnTime > 0) {
            debuglog "+++ turnOnOutput: minOnTimePassed set to false, will toggle in ${minOnTime} min"
            state.minOnTimePassed = false
            runIn(60 * minOnTime, 'setMinOnTimePassed')
        } else {
            state.minOnTimePassed = true
        }
    } else {
        if (state.starting) {
            debuglog "+++ turnOnOutput: humFeedback = ${state.humFeedback}, state.starting=true"
            infostr += ", waiting for humFeedback"
        } else {
            debuglog "+++ turnOnOutput: humFeedback = ${state.humFeedback}, output already on"
            infostr += ", output already on, humFeedback=${state.humFeedback}"
        }
    }
    infolog infostr
}

def turnOffOutput()
{
    def infostr = "+++ turnOffOutput: state.co = ${state.co}"

    //
    // Turn off sequence:
    //  1) Stop compressor
    //  2) Stop fan (if configured)
    //
    // Don't apply any delays, as this will re-introduce moisture on the dehumidifier coils if we keep running the fan.
    //

    if ((state.humFeedback == "on") && !state.stopping) {
        debuglog "+++ turnOffOutput: humFeedback = ${state.humFeedback}, state.stopping = true, off sequence started"
        
        // Set the state.stopping flag; we will reset this after confirmation of the compressor stop command
        state.stopping = true 
        humSwitchOff()
        infostr += ", turning off humControlSwitch"
        if (fanControlSwitch != null) {
            debuglog "+++ turnOffOutput: fanControlSwitch = ${fanControlSwitch}"
            fanSwitchOff()
            infostr += " and fanControlSwitch"
        }
    } else {
        if (state.stopping) {
            debuglog "+++ turnOffOutput: humFeedback = ${state.humFeedback}, state.stopping=true"
            infostr += ", waiting for humFeedback"
        } else {
            debuglog "+++ turnOffOutput: humFeedback = ${state.humFeedback}, output already off"
            infostr += ", output already off, humFeedback=${state.humFeedback}"
        }
    }
    infolog infostr
}

def humSwitchOn() {
    debuglog "+++ humControlSwitch: turning on, state.starting=${state.starting}"
    humControlSwitch.on()
}

def humSwitchOff() {
    debuglog "+++ humControlSwitch: turning off, state.stopping=${state.stopping}"
    humControlSwitch.off()
}

def fanSwitchOn() {
    debuglog "+++ fanControlSwitch: on"
    fanControlSwitch.on()
}

def fanSwitchOff() {
    debuglog "+++ fanControlSwitch: off"
    fanControlSwitch.off()
}

def tstatFanOn() {
    debuglog "+++ tstatFanMode: on"
    tstat.fanOn()
}

def tstatFanCirc() {
    debuglog "+++ tstatFanMode: circulate"
    tstat.fanCirculate()
}

def tstatFanAuto() {
    debuglog "+++ tstatFanMode: auto"
    tstat.fanAuto()
}

def setMinOnTimePassed() {
    debuglog "+++ setMinOnTimePassed: minOnTimePassed set to true, controller can now transition to off state"
    state.minOnTimePassed = true
    infolog "+++ setMinOnTimePassed: timed out, state.minOnTimePassed = true"
    execControlLoop()
}

//
// Utility functions
//
def updateAppLabel(textStr, textColor, def textPrefix=null) {
    //def str = """<span style='color:$textColor'> ($textPrefix $textStr)</span>"""
    def str = (textPrefix != null) ? """<span style='color:$textColor'> ($textPrefix $textStr)</span>""" : """<span style='color:$textColor'> ($textStr)</span>"""
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
