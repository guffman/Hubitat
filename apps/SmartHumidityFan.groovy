/**
*  Smart Humidity Fan
*
*  Turns on a fan when you start taking a shower... turns it back off when you are done.
*    -Uses humidity change rate for rapid response
*    -Timeout option when manaully controled (for stench mitigation)
*
*  Copyright 2018 Craig Romei
*  GNU General Public License v2 (https://www.gnu.org/licenses/gpl-2.0.txt)
*  Modified by Guffman 2019
*
*/

definition(
    name: "Smart Humidity Fan V2",
    namespace: "Guffman",
    author: "Guffman",
    description: "Control a fan (switch) based on relative humidity.",
    category: "Convenience",
    importUrl: "https://raw.githubusercontent.com/guffman/Hubitat/master/apps/SmartHumidityFan.groovy"
)

preferences {
    page(name: "pageConfig") // Doing it this way elimiates the default app name/mode options.
}
def pageConfig()
{
	dynamicPage(name: "", title: "", install: true, uninstall: true, refreshInterval:0) {
		section("General")
		{
		input (name: "customLabel", type: "text", title: "Enter a name for the app (optional)", required: false, defaultValue: "Smart Humidity Fan")
		}
		section("Bathroom Devices")
		{
			paragraph "NOTE: The humidity sensor you select will need to report about 5 min or less."
			input "HumiditySensor", "capability.relativeHumidityMeasurement", title: "Humidity Sensor", required: true
			input "FanSwitch", "capability.switch", title: "Fan Location", required: true
			input "DehumidSwitch", "capability.switch", title: "Dehumidification Mode Tracking Switch", required: false
		}
		section("Fan Activation")
		{
			input "HumidityIncreaseRate", "number", title: "Humidity Increase Rate :", required: true, defaultValue: 3
			input "HumidityThreshold", "number", title: "Humidity Threshold (%RH):", required: false, defaultValue: 65
		}
		section("Fan Deactivation")
		{
			input "HumidityDropTimeout", "number", title: "Minutes to delay turning off the fan after the humidity starts to decrease:", required: true, defaultValue:  15
			input "HumidityDropLimit", "number", title: "What %RH above the %RH threshold to trigger the fan off delay:", required: true, defaultValue:  5
		}
		section("Manual Activation")
		{
			paragraph "When should the fan turn off when turned on manually?"
			input "ManualControlMode", "enum", title: "Off After Manual-On?", required: true, options: ["Manually", "By Humidity", "After Set Time"], defaultValue: "After Set Time"
			paragraph "Minutes to delay turning off the fan?"
			input "ManualOffMinutes", "number", title: "Auto Turn Off Time (minutes)?", required: false, defaultValue: 15
		}
		section("Disable Modes")
		{
			paragraph "Automatic humidity control inhibits (On = Disable):"
            input "autoDisableSwitch", "capability.switch", title: "Auto Humidity Control Disable Switch", required: false
			input "modes", "mode", title: "Select Mode(s) that disable Auto Humidity Control:", multiple: true
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
	state.OverThreshold = false
	state.AutomaticallyTurnedOn = false
	state.TurnOffLaterStarted = false
    subscribe(HumiditySensor, "humidity", HumidityHandler)
    subscribe(FanSwitch, "switch", FanSwitchHandler)
	subscribe(location, "mode", modeChangeHandler)
	state.modeDisable = false
    if (autoDisableSwitch) {
        state.autoDisable = (autoDisableSwitch.currentValue("switch") == "on") ? true : false
        subscribe(autoDisableSwitch, "switch", autoSwitchHandler)
    } else {
        state.autoDisable = false
    }
	state.label = customLabel
    updateAppLabel("Off", "red")
	TurnOffFanSwitch()
}

def updateAppLabel(textStr, textColor) {
  def str = """<span style='color:$textColor'> ($textStr)</span>"""
    app.updateLabel(customLabel + str)
}

def autoSwitchHandler(evt)
{
    switch (evt.value)
    {
        case "on":
        state.autoDisable = true
        debuglog "autoSwitchHandler: Disabling automatic humidity control"
        break
        case "off":
        state.autoDisable = false
        debuglog "autoSwitchHandler: Enabling automatic humidity control"
        break
    }
    debuglog "autoSwitchHandler: state.autoDisable = ${state.autoDisable}"
}

def modeChangeHandler(evt)
{
	def allModes = settings.modes
	if(allModes)
	{
		if(allModes.contains(location.mode))
		{
			debuglog "modeChangeHandler: Entered a disabled mode, turning off the Fan"
			state.modeDisable = true
			updateAppLabel("Off", "red")
		} else {
			state.modeDisable = false
		}
	} 
	else
	{	
		debuglog "modeChangeHandler: Entered an enabled mode"
		state.modeDisable = false
	}
}

def HumidityHandler(evt)
{
	infolog "HumidityHandler:running humidity check"
	debuglog "HumidityHandler: state.OverThreshold = ${state.OverThreshold}"
	debuglog "HumidityHandler: state.AutomaticallyTurnedOn = ${state.AutomaticallyTurnedOn}"
	debuglog "HumidityHandler: state.TurnOffLaterStarted = ${state.TurnOffLaterStarted}"               
	debuglog "HumidityHandler: Before"
	debuglog "HumidityHandler: state.lastHumidity = ${state.lastHumidity}"
	debuglog "HumidityHandler: state.lastHumidityDate = ${state.lastHumidityDate}"
	debuglog "HumidityHandler: state.currentHumidity = ${state.currentHumidity}"
	debuglog "HumidityHandler: state.currentHumidityDate = ${state.currentHumidityDate}"
	debuglog "HumidityHandler: state.StartingHumidity = ${state.StartingHumidity}"
	debuglog "HumidityHandler: state.HighestHumidity = ${state.HighestHumidity}"
	debuglog "HumidityHandler: state.HumidityChangeRate = ${state.HumidityChangeRate}"
	debuglog "HumidityHandler: state.targetHumidity = ${state.targetHumidity}"
	state.OverThreshold = CheckThreshold(evt)
	state.lastHumidityDate = state.currentHumidityDate
	if (state.currentHumidity)
	{
		state.lastHumidity = state.currentHumidity
	}
	else
	{
		state.lastHumidity = 100
	}
	if (!state.StartingHumidity)
	{
		state.StartingHumidity = 100
	}
	if (!state.HighestHumidity)
	{
		state.HighestHumidity = 100
	}
	state.currentHumidity = Double.parseDouble(evt.value.replace("%", ""))
	state.currentHumidityDate = evt.date.time
	state.HumidityChangeRate = state.currentHumidity - state.lastHumidity
	if(state.currentHumidity>state.HighestHumidity)
	{
		state.HighestHumidity = state.currentHumidity
	}
	//state.targetHumidity = state.StartingHumidity+HumidityDropLimit/100*(state.HighestHumidity-state.StartingHumidity)  
	state.targetHumidity = HumidityThreshold+HumidityDropLimit 
	debuglog "HumidityHandler: After"
	debuglog "HumidityHandler: state.lastHumidity = ${state.lastHumidity}"
	debuglog "HumidityHandler: state.lastHumidityDate = ${state.lastHumidityDate}"
	debuglog "HumidityHandler: state.currentHumidity = ${state.currentHumidity}"
	debuglog "HumidityHandler: state.currentHumidityDate = ${state.currentHumidityDate}"
	debuglog "HumidityHandler: state.StartingHumidity = ${state.StartingHumidity}"
	debuglog "HumidityHandler: state.HighestHumidity = ${state.HighestHumidity}"
	debuglog "HumidityHandler: state.HumidityChangeRate = ${state.HumidityChangeRate.round(2)}"
	debuglog "HumidityHandler: state.targetHumidity = ${state.targetHumidity}"

	//if the humidity is high (or rising fast) and the fan is off, kick on the fan
    if (((state.HumidityChangeRate>HumidityIncreaseRate)||state.OverThreshold) && (FanSwitch.currentValue("switch") == "off") && !state.autoDisable && !state.modeDisable)
    {
		state.AutomaticallyTurnedOn = true
		state.TurnOffLaterStarted = false
		state.AutomaticallyTurnedOnAt = new Date().format("yyyy-MM-dd HH:mm")
		infolog "HumidityHandler:Turn On Fan due to humidity increase"
		TurnOnFanSwitch()
		if(DehumidSwitch) {DehumidSwitch.on()}
		updateAppLabel("On - Humidity High/Increasing at ${state.AutomaticallyTurnedOnAt}", "green")
        state.StartingHumidity = state.lastHumidity
        state.HighestHumidity = state.currentHumidity    
		debuglog "HumidityHandler: new state.StartingHumidity = ${state.StartingHumidity}"
		debuglog "HumidityHandler: new state.HighestHumidity = ${state.HighestHumidity}"
		debuglog "HumidityHandler: new state.targetHumidity = ${state.targetHumidity}"
	}
	//turn off the fan when humidity returns to normal and it was kicked on by the humidity sensor
	else if((state.AutomaticallyTurnedOn || ManualControlMode == "By Humidity")&& !state.TurnOffLaterStarted)
	{    
        if(state.currentHumidity<=state.targetHumidity)
        {
            if(HumidityDropTimeout == 0)
            {
                infolog "HumidityHandler:Fan Off"
                TurnOffFanSwitch()
				state.AutomaticallyTurnedOffAt = new Date().format("yyyy-MM-dd HH:mm")
				updateAppLabel("Off - Humidity Returned to Normal at ${state.AutomaticallyTurnedOffAt}", "red")
            }
            else
            {
				infolog "HumidityHandler:Turn Fan off in ${HumidityDropTimeout} minutes."
				state.TurnOffLaterStarted = true
				runIn(60 * HumidityDropTimeout.toInteger(), TurnOffFanSwitchCheckHumidity)
				debuglog "HumidityHandler: state.TurnOffLaterStarted = ${state.TurnOffLaterStarted}"
			}
		}
	}
}

def FanSwitchHandler(evt)
{
	infolog "FanSwitchHandler::Switch changed"
	debuglog "FanSwitchHandler: ManualControlMode = ${ManualControlMode}"
	debuglog "FanSwitchHandler: ManualOffMinutes = ${ManualOffMinutes}"
	debuglog "HumidityHandler: state.AutomaticallyTurnedOn = ${state.AutomaticallyTurnedOn}"
	switch(evt.value)
	{
		case "on":
			if(!state.AutomaticallyTurnedOn && (ManualControlMode == "After Set Time") && ManualOffMinutes)
			{
				if(ManualOffMinutes == 0)
				{
					debuglog "FanSwitchHandler::Fan Off"
					TurnOffFanSwitch()
				}
					else
				{
					debuglog "FanSwitchHandler::Will turn off later"
					runIn(60 * ManualOffMinutes.toInteger(), TurnOffFanSwitch)
					updateAppLabel("On - Manual Off Delay Activated", "green")
				}
			}
			break
        case "off":
			debuglog "FanSwitchHandler::Switch turned off"
			state.AutomaticallyTurnedOn = false
			state.TurnOffLaterStarted = false
		    if(DehumidSwitch) {DehumidSwitch.off()}
            updateAppLabel("Off", "red")
			break
    }
}

def TurnOffFanSwitchCheckHumidity()
{
    debuglog "TurnOffFanSwitchCheckHumidity: Function Start"
	if(FanSwitch.currentValue("switch") == "on")
    {
		debuglog "TurnOffFanSwitchCheckHumidity: state.HumidityChangeRate ${state.HumidityChangeRate}"
		if(state.currentHumidity > state.targetHumidity)
        {
			debuglog "TurnOffFanSwitchCheckHumidity: Didn't turn off fan because humidity rate is ${state.HumidityChangeRate}"
			if(DehumidSwitch) {DehumidSwitch.on()}
			state.AutomaticallyTurnedOn = true
			state.AutomaticallyTurnedOnAt = now()
			state.TurnOffLaterStarted = false
		}
		else
		{
			debuglog "TurnOffFanSwitchCheckHumidity: Turning the Fan off now"
			TurnOffFanSwitch()
			updateAppLabel("Off - Humidity Normal", "red")
		}
	}
}

def TurnOnFanSwitch()
{
    if(FanSwitch.currentValue("switch") == "off")
    {
        infolog "TurnOnFanSwitch:Fan On"
        FanSwitch.on()
    }
}

def TurnOffFanSwitch()
{
    if(FanSwitch.currentValue("switch") == "on")
    {
        infolog "TurnOffFanSwitch:Fan Off"
        FanSwitch.off()
		if(DehumidSwitch) {DehumidSwitch.off()}
        state.AutomaticallyTurnedOn = false
        state.TurnOffLaterStarted = false
        updateAppLabel("Off", "red")
    }
	

}

def CheckThreshold(evt)
{
	double lastevtvalue = Double.parseDouble(evt.value.replace("%", ""))
	if(lastevtvalue >= HumidityThreshold)
	{  
		infolog "IsHumidityPresent: Humidity is above the Threshold"
		return true
	}
	else
	{
		return false
	}
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
