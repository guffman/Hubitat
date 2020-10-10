definition(
    name: "Average Temperatures",
    namespace: "hubitat",
    author: "Bruce Ravenel",
    description: "Average some temperature sensors",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "")

preferences {
	page(name: "mainPage")
}

def mainPage() {
	dynamicPage(name: "mainPage", title: " ", install: true, uninstall: true) {
		section {
			input "thisName", "text", title: "Name this temperature averager", submitOnChange: true
			if(thisName) app.updateLabel("$thisName")
			input "tempSensors", "capability.temperatureMeasurement", title: "Select Temperature Sensors", submitOnChange: true, required: true, multiple: true
			paragraph "Enter weight factors and offsets"
			tempSensors.each {
				input "weight$it.id", "decimal", title: "$it ($it.currentTemperature)", defaultValue: 1.0, submitOnChange: true, width: 3
				input "offset$it.id", "decimal", title: "$it Offset", defaultValue: 0.0, submitOnChange: true, range: "*..*", width: 3
			}
			input "useRun", "number", title: "Compute running average over this many sensor events:", defaultValue: 1, submitOnChange: true
            input "lowClamp", "decimal", title: "Temperature clamp value low:", defaultValue: 50.0, required: true, submitOnChange: false
            input "highClamp", "decimal", title: "Temperature clamp value high:", defaultValue: 100.0, required: true, submitOnChange: false
            input "deltaClamp", "decimal", title: "Temperature clamp value delta:", defaultValue: 2.0, required: true, submitOnChange: false            
			if(tempSensors) paragraph "Current sensor average is ${averageTemp()}°"
			if(useRun > 1) {
				initRun()
				if(tempSensors) paragraph "Current running average is ${averageTemp(useRun)}°"
			}
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
	def averageDev = getChildDevice("AverageTemp_${app.id}")
	if(!averageDev) averageDev = addChildDevice("hubitat", "Virtual Temperature Sensor", "AverageTemp_${app.id}", null, [label: thisName, name: thisName])
	averageDev.setTemperature(averageTemp())
	subscribe(tempSensors, "temperature", handler)
}

def initRun() {
	def temp = averageTemp()
//	if(!state.run) {
		state.run = []
		for(int i = 0; i < useRun; i++) state.run += temp
//	}
//    log.info "initRun: state.run = ${state.run}"
}

def averageTemp(run = 1) {
	def total = 0
	def n = 0
    def averageDev = getChildDevice("AverageTemp_${app.id}")
    def currentTemp = averageDev.currentValue("temperature")
    if (!lowClamp) lowClamp = 0.0
    if (!highClamp) highClamp = 100.0
	tempSensors.each {
        if (it.currentTemperature <=lowClamp || it.currentTemperature >= highClamp) log.warn "Temperature sensor ${it.label} reading ${it.currentTemperature} out of clamp range ${lowClamp}-${highClamp}"
		def offset = settings["offset$it.id"] != null ? settings["offset$it.id"] : 0       
		total += (it.currentTemperature + offset) * (settings["weight$it.id"] != null ? settings["weight$it.id"] : 1)
		n += settings["weight$it.id"] != null ? settings["weight$it.id"] : 1
	}
	def result = total / (n = 0 ? tempSensors.size() : n)
    
	if(run > 1) {
		total = 0
		state.run.each {total += it}
		result = total / run
	}
    if ((result > lowClamp) && (result < highClamp) && (Math.abs(currentTemp - result) < deltaClamp)) {
		state.clamped = false
        return result.toDouble().round(1)
    } else {
		state.clamped = true
        log.warn "Temperature average clamped: ignoring calculated average value ${result}"
        return currentTemp
    }
}

def handler(evt) {
	def averageDev = getChildDevice("AverageTemp_${app.id}")
	def avg = averageTemp()
	def avgStr = ""
	if(useRun > 1) {
        //log.info "Event change before: state.run = ${state.run}"
		state.run = state.run.drop(1) + avg
        //log.info "Event change after: state.run = ${state.run}"
		avg = averageTemp(useRun)
	}
	averageDev.setTemperature(avg)
	avgStr = String.format("%.1f", avg) + "°F"
    avgStr = avgStr.trim()
//	log.info "Average sensor temperature = ${averageTemp()}°" + (useRun > 1 ? "    Running average is $avg°" : "")
	state.clamped ? updateAppLabel("Clamped", "orange", "${avgStr},") : updateAppLabel("${avgStr}", "green")
}

def updateAppLabel(textStr, textColor, textPrefix = '') {
    def str = (textPrefix = null) ? """<span style='color:$textColor'> ($textPrefix $textStr)</span>""" : """<span style='color:$textColor'> ($textStr)</span>"""
    app.updateLabel(thisName + str)
}
