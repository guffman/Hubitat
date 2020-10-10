/*
*  Smart Humidistat
*
*  Adjusts a switch/outlet based on a target high / low humidity 
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
*
*
*/

def version() {"v0.2"}

definition(
    name: "Smart Humidistat",
    namespace: "Guffman",
    author: "Guffman",
    description: "Control a switch/outlet to drive humidity to within a set high and low limit, using a differential gap control algorithm.",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/guffman/Hubitat/master/apps/SmartHumidistat.groovy"
)
preferences {
    page(name: "pageConfig") // Doing it this way eliminates the default app name/mode options.
}

def pageConfig() {
    dynamicPage(name: "", title: "", install: true, uninstall: true, refreshInterval:0) {
        section("General") 
        {
		input (name: "customLabel", type: "text", title: "Enter a name for the app (optional)", required: false, defaultValue: "Smart Humidistat")
		}
	    section("Gap Controller Input/Output Devices")
		{
            input "loopSpt", "capability.relativeHumidityMeasurement", title: "Humidity Setpoint:", required: true, multiple: false
			input "humiditySensor", "capability.relativeHumidityMeasurement", title: "Humidity Sensor:", required: true, multiple: false
			input "humControlSwitch", "capability.switch", title: "Humidity Control Output - Switch or Outlet:", required: true, multiple: false
            input "fanControlSwitch", "capability.switch", title: "Fan Control Output - Switch or Outlet:", required: false
		}
		section("Gap Controller Settings")
		{
            input "loopDband", "decimal", title: "Deadband (%)", required: true, defaultValue: 1.0
            input "loopSptOffsetAway", "decimal", title: "Setpoint offset when location Away mode (%)", required: true, defaultValue: 1.0
            input "onDelay", "number", title: "Humidity Control Output On/Off Delay(sec)", required: true, defaultValue: 60
            input "minOnTime", "number", title: "Humidity Control Minimum On Time (mins)", required: true, defaultValue: 5
        }
        section("Associated Devices Settings")
        {
            input "powerMeter", "capability.powerMeter", title: "Dehumidifier Power Meter:", required: true, multiple: false
            input "humFeedbackPwrLimit", "number", title: "Humidity Control Feedback Power Threshold (W)", required: true, defaultValue: 250
            input "humFeedbackSwitch", "capability.switch", title: "Humidity Control Feedback Indicator - Switch:", required: true, multiple: false
            input "fanFeedbackPwrLimit", "number", title: "Fan Control Feedback Power Threshold (W)", required: false, defaultValue: 50
            input "fanFeedbackSwitch", "capability.switch", title: "Fan Control Feedback Indicator - Switch:", required: false, multiple: false
            input "tstat", "capability.thermostat", title: "Associated Thermostat:", required: true, multiple: false
            input "tstatFanControl", "bool", title: "Thermostat Fan Mode Circulate/On Follows Dehumidifying State", required: false, defaultValue: true
            input "tstatFanResetDelay", "number", title: "Thermostat Fan Mode Return to Circulate Delay (sec)", required: false, defaultValue: 15
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

    // Not sure what to use auto mode for yet, need to work on offPermissive logic
    state.autoMode = true
    state.offPermissive = true
    state.pv  = getAvgHumid()
    state.pvStr = String.format("%.1f", state.pv) + "%"
    
    subscribe(location, "mode", modeChangeHandler)
    setModePermissive()
   
    subscribe(tstat, "thermostatFanMode", tstatFanModeHandler)
    
    subscribe(tstat, "thermostatMode", tstatModeHandler)
    
    subscribe(humControlSwitch, "switch", humSwitchHandler)
    if (humControlSwitch.currentValue("switch") == "on") {
        state.co = true
    } else {
        state.co = false
    }
         
    if (fanControlSwitch != null) {
        subscribe(fanControlSwitch, "switch", fanSwitchHandler)
        if (fanFeedbackSwitch.currentValue("switch") == "on") {
            state.fanFeedback = true
        } else {
            state.fanFeedback = false
        }
    }

    debuglog "Initializing: pv=${state.pvStr}, state.co=${state.co}"

    subscribe(powerMeter, "power", powerMeterHandler)
    state.power = powerMeter.currentValue("power")
    calcEquipmentStates()
    subscribe(humiditySensor, "humidity", humidityHandler)
    subscribe(loopSpt, "humidity", sptHandler)
    calcSetpoints()
    execControlLoop()
    
    state.label = customLabel
}

def getAvgHumid()
{
    def sum = 0.0
    def count = 0
    humiditySensor.each { sensor -> 
        sum += sensor.currentValue("humidity").toFloat()
        count ++
    }
    return (sum / count)
}

def setModePermissive()
{
    debuglog "setModePermissive: location.mode = ${location.mode}"
    debuglog "setModePermissive: settings.modes = ${modes}"
    
    state.modePermissive = true
    if(modes != null) {
        if (modes.contains(location.mode))
		{
			debuglog "setModePermissive: Entered a disabled mode, turning off mode permissive"
			state.modePermissive = false
		}
	    else
	    {	
		    debuglog "setModePermissive: Entered an enabled mode, turning on mode permissive"
		    state.modePermissive = true
	    }
    }
    else
    {
        debuglog "setModePermissive: No modes specified in configuration"
    }
}

def modeChangeHandler(evt)
{
    infolog "modeChangeHandler: mode changed: ${evt.value}"

    setModePermissive()
    execControlLoop()
}

def humidityHandler(evt)
{
    infolog "humidityHandler: humidity changed: ${evt.value}"
    debuglog "humidityHandler: mode permissive = ${state.modePermissive}"
    debuglog "humidityHandler: controller output = ${state.co}"

    state.pv = getAvgHumid()
    state.pvStr = String.format("%.1f", state.pv) + "%"
    debuglog "humidityHandler: pv = ${state.pv}"
    
    execControlLoop()
}

def sptHandler(evt)
{
    infolog "sptHandler: setpoint changed: ${evt.value}"
    debuglog "sptHandler: mode permissive = ${state.modePermissive}"
    debuglog "sptHandler: controller output = ${state.co}"

    state.pv = getAvgHumid()
    state.pvStr = String.format("%.1f", state.pv) + "%"
    debuglog "sptHandler: pv = ${state.pv}"
    calcSetpoints()
    execControlLoop()
}

def calcSetpoints() 
{
    infolog "calcSetpoints: loopDband=${loopDband}"
    spt = loopSpt.currentValue("humidity").toFloat()
    infolog "calcSetpoints: setpoint=${spt}"
    state.loopSptLow = (spt - (loopDband / 2.0))
    state.loopSptHigh = (spt + (loopDband / 2.0))
    debuglog "calcSetpoints: loopSptLow=${state.loopSptLow}"
    debuglog "calcSetpoints: loopSptHigh=${state.loopSptHigh}"
        
}

def humSwitchHandler(evt) 
{
    infolog "humSwitchHandler: Switch changed: ${evt.value}"
    debuglog "humSwitchHandler: mode permissive = ${state.modePermissive}"
    
	switch(evt.value)
	{
		case "on":
            state.co = true
			break
        case "off":
            state.co = false
			break
    }
}

def powerMeterHandler(evt)
{
    infolog "powerMeterHandler: Power changed: ${evt.value}"
    state.power = evt.value
    calcEquipmentStates()
}

def calcEquipmentStates() 
{
    pwr = state.power.toDouble()
    infolog "calcEquipmentStates: pwr=${pwr}"
    hfb = (pwr > humFeedbackPwrLimit) ? true : false
    ffb = (pwr > fanFeedbackPwrLimit) ? true : false
    
    infolog "calcEquipmentStates: humFeedbackSwitch = ${hfb}"
    state.humFeedback = hfb
    if (hfb) {
        humFeedbackSwitch.on()
        if (tstatFanControl) {
            debuglog "calcEquipmentStates: tstatFanControl = ${tstatFanControl}, setting tstat fan to on"
            runIn(15, 'tstatFanOn')
        }
    } else {
        humFeedbackSwitch.off()
        if (tstatFanControl) {
            debuglog "calcEquipmentStates: tstatFanControl = ${tstatFanControl}, tstatFanResetDelay = ${tstatFanResetDelay}, setting tstat fan to circ"
            runIn(tstatFanResetDelay, 'tstatFanCirc')
        }
    }
    
    if (fanFeedbackSwitch != null) {
        infolog "calcEquipmentStates: fanFeedbackSwitch = ${ffb}"
        state.fanFeedback = ffb
        ffb ? fanFeedbackSwitch.on() : fanFeedbackSwitch.off()
    }
}

def fanSwitchHandler(evt) 
{
    infolog "fanSwitchHandler: Switch changed: ${evt.value}"
    debuglog "fanSwitchHandler: mode permissive = ${state.modePermissive}"
    
	switch(evt.value)
	{
		case "on":
            state.co = true
			break
        case "off":
            state.co = false
			break
    }
    state.fanCo = state.co
}

def tstatModeHandler(evt)   
{
    infolog "tstatModeHandler: Thermostat mode changed: ${evt.value}"
}

def tstatFanModeHandler(evt)
{
    infolog "tstatFanModeHandler: Thermostat fan mode changed: ${evt.value}"
}

def execControlLoop()
{
    if (state.modePermissive) {
        
        debuglog "execControlLoop: evaluating controller state"

        // Compute excursion states
        
        if (location.mode == "Away") {
            state.excursionLow = (state.pv < (state.loopSptLow + loopSptOffsetAway))
            state.excursionHigh = (state.pv > (state.loopSptHigh + loopSptOffsetAway))
        } else {
            state.excursionLow = (state.pv < state.loopSptLow)
            state.excursionHigh = (state.pv > state.loopSptHigh)
        }
            
        // Compute new controller state
        if (state.excursionLow) {
            turnOffOutput()
            updateAppLabel("Below Low Limit - Output Off", "green", state.pvStr)
            debuglog "execControlLoop: pv ${state.pv} < spt ${state.loopSptLow} ->excursionLow, turnOffOutput"
        } else if (state.excursionHigh) {
            updateAppLabel("Above High Limit - Output On", "red", state.pvStr)
            debuglog "execControlLoop: pv ${state.pv} > spt ${state.loopSptHigh} -> excursionHigh, turnOnOutput"
            turnOnOutput()
        } else {
            state.co ? updateAppLabel("Within Limits - Output On", "green", state.pvStr) : updateAppLabel("Within Limits - Output Off", "green", state.pvStr)
            debuglog "execControlLoop: within limits, controller output unchanged (${state.co})"
        }
    } else {
        updateAppLabel("Controller Disabled in current Mode - Output Off", "red")
        debuglog "execControlLoop: controller disabled in current Mode, calling turnOffOutput"
        turnOffOutput()
    }
}

def turnOnOutput()
{
    //
    // Turn on sequence:
    //  1) Start fan (if configured)
    //  2) Wait onDelay
    //  3) Start compressor
    //
    debuglog "turnOnOutput:"
    if (!state.humFeedback) {
        debuglog "turnOnOutput: humFeedback is ${state.humFeedback}, on sequence started"
        if (fanControlSwitch != null) {
            debuglog "turnOnOutput: onDelay = ${onDelay}, fanControlSwitch = ${fanControlSwitch}"
            fanSwitchOn()
            runIn(onDelay, 'humSwitchOn')
        } else {    
            humSwitchOn()
        }
    } else {
        debuglog "turnOnOutput: humFeedback is ${state.humFeedback}"
    }
}

def turnOffOutput()
{
    //
    // Turn off sequence:
    //  1) Stop compressor
    //  2) Wait onDelay
    //  3) Stop fan (if configured) 
    //
    debuglog "turnOffOutput:"
    if (state.humFeedback) {
        debuglog "turnOffOutput: humFeedback is ${state.humFeedback}, off sequence started, calling humSwitchOff"
        humSwitchOff()
        if (fanControlSwitch != null) {
            debuglog "turnOffOutput: onDelay = ${onDelay}, fanControlSwitch = ${fanControlSwitch}"
            runIn(onDelay, 'fanSwitchOff')
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
