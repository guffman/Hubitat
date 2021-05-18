//
// This app is derived from the community 'Average Temperatures' app written by Bruce Ravenel. Td calculation from Ashok Aayar NodeRed function code.
//
//
definition(
    name: "Virtual Dewpoint Sensor",
    namespace: "Guffman",
    author: "Guffman",
    description: "Calculate a dewpoint, given a humidity and a temperature",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "")

preferences {
	page(name: "mainPage")
}

def mainPage() {
	dynamicPage(name: "mainPage", title: " ", install: true, uninstall: true) {
		section {
			input "thisName", "text", title: "Name this virtual dewpoint sensor", submitOnChange: true
            if(thisName) {
                app.updateLabel("$thisName")
                state.label = thisName
            }
			input "tempSensor", "capability.temperatureMeasurement", title: "Select Temperature Sensor", submitOnChange: true, required: true, multiple: false
			input "humidSensor", "capability.relativeHumidityMeasurement", title: "Select Humidity Sensor", submitOnChange: true, required: true, multiple: false
			input "lowClamp", "decimal", title: "Dewpoint clamp value low:", defaultValue: 30.0, required: true, submitOnChange: false
            input "highClamp", "decimal", title: "Dewpoint clamp value high:", defaultValue: 70.0, required: true, submitOnChange: false        
		}
	}
}

def installed() {
	initialize()
}

def updated() {
	unsubscribe()
	initialize()
}

def initialize() {
	def dewpointDev = getChildDevice("Dewpoint_${app.id}")
	
	dewpointDev.setTemperature(initDewpoint)
	subscribe(tempSensor, "temperature", tempHandler)
    subscribe(humidSensor, "humidity", humidHandler)
    calcDewpoint()
}

def calcDewpoint() {

    def dewpointDev = getChildDevice("Dewpoint_${app.id}")
    def currentDewpoint = dewpointDev.currentValue("temperature")
    def currentTemp = tempSensor.currentValue("temperature")
    def currentHumid = humidSensor.currentValue("humidity")
    def tempC = f2c(currentTemp)
    def dewpointC = dpC(tempC, currentHumid)
    def result = c2f(dewpointC)
    def dewpointF = result.toDouble().round(1)
    
    //log.info "In calcDewpoint, currentDewpoint=${currentDewpoint}, currentTemp=${currentTemp}, currentHumid=${currentHumid}, result=${result}, dewpointF=${dewpointF}" 
	
	
//Sensor validity tests
    dptStr = String.format("%.1f", dewpointF) + "°F"
    dptStr = dptStr.trim()
    
    if ((result > lowClamp) && (result < highClamp)) {
		state.clamped = false
        updateAppLabel("${dptStr}", "green")
        dewpointDev.setTemperature(dewpointF)
    } else {
		state.clamped = true
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

def tempHandler(evt) {
    //log.info "In tempHandler"
    calcDewpoint()
}

def humidHandler(evt) {
    //log.info "In humidHandler"
    calcDewpoint()
}

def updateAppLabel(textStr, textColor, def textPrefix=null) {
    def str = (textPrefix != null) ? """<span style='color:$textColor'> ($textPrefix $textStr)</span>""" : """<span style='color:$textColor'> ($textStr)</span>"""
    app.updateLabel(state.label + str)
}
