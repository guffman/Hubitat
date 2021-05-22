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
*
*
*/

def version() {"v0.2"}

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
            input "onDelay", "number", title: "Dehumidify Control Output On/Off Delay(sec)", required: true, range: "5..120", defaultValue: 60
            input "minOnTime", "number", title: "Dehumidify Control Minimum On Time (mins)", required: true, range: "0..30", defaultValue: 5
        }       
        section("Associated Devices Settings")
        {
            input "powerMeter", "capability.powerMeter", title: "Dehumidifier Power Meter:", required: true, multiple: false
            input "humFeedbackPwrLimit", "number", title: "Dehumidify Control Feedback Power Threshold (W)", required: true, defaultValue: 250
            input "humFeedbackSwitch", "capability.switch", title: "Dehumidify Control Feedback Indicator - Switch:", required: true, multiple: false
            input "fanFeedbackPwrLimit", "number", title: "Fan Control Feedback Power Threshold (W)", required: false, defaultValue: 50
            input "fanFeedbackSwitch", "capability.switch", title: "Fan Control Feedback Indicator - Switch:", required: false, multiple: false
            input "tstat", "capability.thermostat", title: "Associated Thermostat:", required: true, multiple: false
            input "tstatFanControl", "bool", title: "Thermostat Fan Mode (On/Circ) Follows Dehumidifying State", required: false, defaultValue: true
            input "tstatFanResetDelay", "number", title: "Thermostat Fan Mode Return to Circ Delay (sec)", required: false, range: "0..300", defaultValue: 60
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
	initialize()
}

def initialize()
{
    infolog "Initializing"

    // Not sure what to use auto mode for yet
    state.autoMode = true
    
    // offPermissive is used to prevent the controller transitioning to off prior to minOnTime. Prevents short-cycling of the dehumidifier.
    // While offPermissive is true, the controller is permitted to turn off.
    state.offPermissive = true
    
    // Need to initialize the controller state - use the humidControlSwitch state
    state.co = (humControlSwitch.currentValue("switch") == "on") ? true : false
/*    if (humControlSwitch.currentValue("switch") == "on") {
        state.co = true
    } else {
        state.co = false
    }
*/
    subscribe(humControlSwitch, "switch", humSwitchHandler)   
    
    if (fanControlSwitch != null) {
        subscribe(fanControlSwitch, "switch", fanSwitchHandler)
    }
    
    state.pv  = tempSensor.currentValue("temperature").toFloat()
    state.pvStr = String.format("%.1f", state.pv) + "°F"
    subscribe(tempSensor, "temperature", dewpointHandler)
    
    subscribe(loopSpt, "temperature", sptHandler)
    subscribe(tstat, "thermostatFanMode", tstatFanModeHandler)
    subscribe(tstat, "thermostatMode", tstatModeHandler)
    
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
    debuglog "dewpointHandler: controller output = ${state.co}"

    state.pv = tempSensor.currentValue("temperature").toFloat()
    state.pvStr = String.format("%.1f", state.pv) + "°F"
    
    execControlLoop()
}

def sptHandler(evt)
{
    infolog "sptHandler: setpoint changed: ${evt.value}"
    debuglog "sptHandler: state.pv = ${state.pv}, controller output = ${state.co}"

    calcSetpoints()
    execControlLoop()
}

def powerMeterHandler(evt)
{
    infolog "powerMeterHandler: Power changed: ${evt.value}"
    debuglog "powerMeterHandler: state.pv = ${state.pv}, controller output = ${state.co}"
    state.power = evt.value
    calcEquipmentStates()
}

def fanSwitchHandler(evt) 
{
    infolog "fanSwitchHandler: Switch changed: ${evt.value}"
    debuglog "fanSwitchHandler: controller output = ${state.co}"
    
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
    debuglog "humSwitchHandler: controller output = ${state.co}"
    
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
}

def tstatFanModeHandler(evt)
{
    infolog "tstatFanModeHandler: Thermostat fan mode changed: ${evt.value}"
}

def calcSetpoints() 
{
    infolog "calcSetpoints:"
    debuglog "calcSetpoints: controller output = ${state.co}"
    
    spt = loopSpt.currentValue("temperature").toFloat()
    state.loopSptLow = (spt - (loopDband / 2.0))
    state.loopSptHigh = (spt + (loopDband / 2.0))
    infolog "calcSetpoints: setpoint=${spt}, loopDband=${loopDband}, loopSptLow=${state.loopSptLow}, loopSptHigh=${state.loopSptHigh}"
}

def calcEquipmentStates() 
{
    infolog "calcEquipmentStates:"
    debuglog "calcEquipmentStates: controller output = ${state.co}"
    
    pwr = state.power.toDouble()
    debuglog "calcEquipmentStates: pwr=${pwr}"
    
    state.humFeedback = (pwr > humFeedbackPwrLimit) ? true : false
    debuglog "calcEquipmentStates: humFeedbackSwitch = ${state.humFeedback}"
    state.humFeedback ? humFeedbackSwitch.on() : humFeedbackSwitch.off()

    if (fanFeedbackSwitch != null) {
        state.fanFeedback = (pwr > fanFeedbackPwrLimit) ? true : false
        debuglog "calcEquipmentStates: fanFeedbackSwitch = ${state.fanFeedback}"
        state.fanFeedback ? fanFeedbackSwitch.on() : fanFeedbackSwitch.off()
    }
}

def execControlLoop()
{     
    infolog "execControlLoop:"
    debuglog "execControlLoop: pv = ${state.pv}"

    // Compute excursion states
        
    state.excursionLow = (state.pv < state.loopSptLow)
    state.excursionHigh = (state.pv > state.loopSptHigh)
            
    // Compute new controller state
    if (state.excursionLow && state.offPermissive) {
        debuglog "execControlLoop: pv ${state.pv} < spt ${state.loopSptLow} ->excursionLow, offPermissive = ${state.offPermissive}, turnOffOutput"
        state.co = false
        turnOffOutput()
        updateAppLabel("Below Low Limit - Output Off", "green", state.pvStr)

    } else if (state.excursionHigh) {
        debuglog "execControlLoop: pv ${state.pv} > spt ${state.loopSptHigh} -> excursionHigh, turnOnOutput"
        updateAppLabel("Above High Limit - Output On", "red", state.pvStr)
        state.co = true
        turnOnOutput()
    } else {
        debuglog "execControlLoop: within limits, controller output unchanged (${state.co})"
        state.co ? updateAppLabel("Within Limits - Output On", "green", state.pvStr) : updateAppLabel("Within Limits - Output Off", "green", state.pvStr)
    }
}

def turnOnOutput()
{
    infolog "turnOnOutput:"
    debuglog "turnOnOutput: controller output = ${state.co}"
    //
    // Turn on sequence:
    //  1) Start fan (if configured)
    //  2) Wait onDelay seconds
    //  3) Start compressor
    //  4) Set tstat fan mode to on (if configured)
    //  5) Set offPermissive to false 
    //

    if (!state.humFeedback) {
        debuglog "turnOnOutput: humFeedback is ${state.humFeedback}, on sequence started"
        if (fanControlSwitch != null) {
            debuglog "turnOnOutput: onDelay = ${onDelay}, fanControlSwitch = ${fanControlSwitch}, minOnTime = ${minOnTime}"
            fanSwitchOn()
            runIn(onDelay, 'humSwitchOn')
        } else {    
            humSwitchOn()
        }
        if (tstatFanControl) {
            debuglog "turnOnOutput: tstatFanControl = ${tstatFanControl}, setting tstat fan to on"
            tstatFanOn()
        }
        state.offPermissive = false
        runIn(60 * minOnTime, 'resetOffPermissive')
        debuglog "offPermissive set to false, will reset in ${minOnTime} minutes"
    } else {
        debuglog "turnOnOutput: humFeedback is ${state.humFeedback}, output already on"
    }

}

def turnOffOutput()
{
    infolog "turnOffOutput:"
    debuglog "turnOffOutput: controller output = ${state.co}"
    //
    // Turn off sequence:
    //  1) Stop compressor
    //  2) Wait onDelay seconds
    //  3) Stop fan (if configured)
    //  4) Set tstat fan mode to circ (if configured), after tstatFanResetDelay minutes
    //

    if (state.humFeedback) {
        debuglog "turnOffOutput: humFeedback is ${state.humFeedback}, off sequence started"
        humSwitchOff()
        if (fanControlSwitch != null) {
            debuglog "turnOffOutput: onDelay = ${onDelay}, fanControlSwitch = ${fanControlSwitch}"
            runIn(onDelay, 'fanSwitchOff')
        }
        if (tstatFanControl) {
            debuglog "turnOffOutput: tstatFanControl = ${tstatFanControl}, setting tstat fan to circ after ${tstatFanResetDelay} seconds"
            runIn(tstatFanResetDelay, 'tstatFanCirc')
        }
    } else {
        debuglog "turnOffOutput: humFeedback is ${state.humFeedback}, output already off"
    }

}

def humSwitchOn() {
    debuglog "humControlSwitch: on"
    humControlSwitch.on()
}

def humSwitchOff() {
    debuglog "humControlSwitch: off"
    humControlSwitch.off()
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
    debuglog "resetOffPermissive:"
    debuglog "offPermissive set to true, controller can now transition to off state"
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
