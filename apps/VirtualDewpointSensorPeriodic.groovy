/*
* This app is derived from the community 'Average Temperatures' app written by Bruce Ravenel.
* Dewpoint calculation algorithm thanks to Ashok Aiyar in the Hubitiat Community.
* Use a periodic calculation interval rather than event-driven, due to the irregular update timing from the sensors. The smoothing works well with a continuous time series.
*
*  Change History:
*
*    Date        Who            What
*    ----        ---            ----
*    2020-09-15  Guffman        Original Creation
*    2020-10-25  Guffman        Added clamping due to some random wild readings from the humidity sensors.
*    2021-02-04  Guffman        Revised initialize code, added smoothing feature. 
*    2021-05-21  Dlmcpaul	Added support for Celsius temperature scale
*    2023-04-13  Guffman	Added offset options for temperature and humidity sensors. Added logging helper functions.
*
*/

definition(
    name: "Virtual Dewpoint Sensor - Periodic",
    namespace: "Guffman",
    author: "Guffman",
    description: "Periodically calculate a dewpoint in degrees Farenheit, given a humidity and a temperature. Allow for time series smoothing using an exponential smoothing filter algorithm.",
    category: "Convenience",
    importUrl: "https://raw.githubusercontent.com/guffman/Hubitat/master/apps/VirtualDewpointSensorPeriodic.groovy",
    iconUrl: "",
    iconX2Url: "")

preferences {
	page(name: "mainPage")
}

def mainPage() {
	dynamicPage(name: "mainPage", title: " ", install: true, uninstall: true) {
		section {
			input "thisName", "text", title: "Name this virtual dewpoint sensor", required: true, submitOnChange: true
            if(thisName) {
                app.updateLabel("$thisName")
                state.label = thisName
            }
			input "tempSensor", "capability.temperatureMeasurement", title: "Select Temperature Sensor", submitOnChange: true, required: true, multiple: false
            input "tempOffset", "decimal", title: "Temperature offset (+/-):", defaultValue: 0.0, required: false, submitOnChange: false
			input "humidSensor", "capability.relativeHumidityMeasurement", title: "Select Humidity Sensor", submitOnChange: true, required: true, multiple: false
            input "humidOffset", "decimal", title: "Humidity offset (+/-):", defaultValue: 0.0, required: false, submitOnChange: false
			input "lowClamp", "decimal", title: "Dewpoint clamp value low:", defaultValue: 30.0, required: true, submitOnChange: false
            input "highClamp", "decimal", title: "Dewpoint clamp value high:", defaultValue: 70.0, required: true, submitOnChange: false
            input "alpha", "decimal", title: "Exponential smoothing filter factor (1.0 = no smoothing, 0.1 = maximum smoothing):", defaultValue: 1.0, required: true, range: "0.1..1.0", submitOnChange: false
            input "calcInterval", "enum", title: "Calculation update interval", required: true, options: ["1 min", "5 min", "10 min", "15 min"], defaultValue: "5 min"
            input "logEnable", "bool", title: "Enable debug logging", defaultValue: true
		}
	}
}

def installed() {
	initialize()
}

def updated() {
    log.info "updating..."
    unschedule()
	log.warn "debug logging is: ${logEnable == true}"
	if (logEnable) runIn(1800, logsOff)
    initialize()
}

def initialize() {
    
    // Check if the device exists. If not create it
	def dewpointDev = getChildDevice("Dewpoint_${app.id}")
    
    if(!dewpointDev) {
        // Create the virtual temperature sensor, initialize it at the current %RH as a rough first guess
        dewpointDev = addChildDevice("hubitat", "Virtual Temperature Sensor", "Dewpoint_${app.id}", null, [label: thisName, name: thisName])
        dewpointDev.setTemperature(humidSensor.currentValue("humidity"))
    }
    
    if (tempOffset == null) tempOffset = 0.0
    if (humidOffset == null) humidOffset = 0.0
    logDebug "tempOffset = ${tempOffset}, humidOffset = ${humidOffset}"
    
    calcDewpoint()
    
    // Schedule updates
    switch (calcInterval)
    {
        case "1 min":
            runEvery1Minute("calcDewpoint")
            break
        case "5 min":
            runEvery5Minutes("calcDewpoint")
            break
        case "10 min":
            runEvery10Minutes("calcDewpoint")
            break
        case "15 min":
            runEvery15Minutes("calcDewpoint")
            break
    }
}

def calcDewpoint() {

    // Get the prior Td value
    def dewpointDev = getChildDevice("Dewpoint_${app.id}")
    def prevDewpoint = dewpointDev.currentValue("temperature").toDouble()
    
    // Compute new Td from current T and %RH
    def currentTemp = tempSensor.currentValue("temperature").toDouble() + tempOffset
    def currentHumid = humidSensor.currentValue("humidity").toDouble() + humidOffset

    def tempC = (location.temperatureScale == "F") ? f2c(currentTemp) : currentTemp
    
    def dewpointC = dpC(tempC, currentHumid)
    
    def result = (location.temperatureScale == "F") ? c2f(dewpointC) : dewpointC
    
    def newDewpoint = result.toDouble().round(1)
    
    logDebug "In calcDewpoint, prevDewpoint=${prevDewpoint}, currentTemp=${currentTemp}, currentHumid=${currentHumid}, result=${result}, newDewpoint=${newDewpoint}" 
    
    // Validity tests 
    if ((result >= lowClamp) && (result <= highClamp)) {
        
        // Valid Td value. Compute filter, update the Td device.
		state.clamped = false
        def smoothedResult = (alpha * newDewpoint) + ((1.0 - alpha) * prevDewpoint)
        def smoothedDewpoint = smoothedResult.toDouble().round(1)
        logDebug "In calcDewpoint, smoothedResult=$smoothedResult, smoothedDewpoint=$smoothedDewpoint"
        dewpointDev.setTemperature(smoothedDewpoint)
        
        // Update the app label for fancy-ness.
        def dptStr = String.format("%.1f", smoothedDewpoint) + "°" + location.temperatureScale
        dptStr = dptStr.trim()
        updateAppLabel("${dptStr}", "green")
        
    } else {
        // Outside clamp limits. Don't update the Td device and don't perform smoothing.
		state.clamped = true
        
        // Update the app label for fancy-ness.
        def dptStr = String.format("%.1f", newDewpoint) + "°" + location.temperatureScale
        dptStr = dptStr.trim()
        if (result < lowClamp) {
            log.warn "Dewpoint clamped: calculated value ${dptStr} below ${lowClamp}"
            updateAppLabel("Below Low Limit - Clamped", "orange", dptStr)
        } else if (result > highClamp) {
            log.warn "Dewpoint clamped: calculated value ${dptStr} above ${highClamp}"
            updateAppLabel("Above High Limit - Clamped", "orange", dptStr)
        }
    }
}

def f2c (degF) {
    def degC = 5 * ((degF-32)/9)
    return degC
}

def c2f (degC) {
    def degF = 32 + (9*(degC/5))
    return degF
}

def dpC (T, RH) {
    def Td = 243.04 * (Math.log(RH/100)+((17.625*T)/(243.04+T)))/(17.625-Math.log(RH/100)-((17.625*T)/(243.04+T)))
    return Td                       
}
//
// Utility functions
//
private logDebug(msg) {
	if (settings?.logEnable) log.debug state.label + " " + "${msg}"
}

def logsOff() {
	log.warn "debug logging disabled..."
	device.updateSetting("logEnable", [value: "false", type: "bool"])
}

private getDescriptionText(msg) {
	def descriptionText = "${device.displayName} ${msg}"
	if (settings?.txtEnable) log.info "${descriptionText}"
	return descriptionText
}

def updateAppLabel(textStr, textColor, def textPrefix=null) {
    def str = (textPrefix != null) ? """<span style='color:$textColor'> ($textPrefix $textStr)</span>""" : """<span style='color:$textColor'> ($textStr)</span>"""
    app.updateLabel(state.label + str)
}
