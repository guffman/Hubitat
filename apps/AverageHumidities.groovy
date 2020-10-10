definition(
    name: "Average Humidities",
    namespace: "hubitat",
    author: "Bruce Ravenel",
    description: "Average some humidity sensors",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "")

preferences {
	page(name: "mainPage")
}

def mainPage() {
	dynamicPage(name: "mainPage", title: " ", install: true, uninstall: true) {
		section {
			input "thisName", "text", title: "Name this humidity averager", submitOnChange: true
			if(thisName) app.updateLabel("$thisName")
			input "humidSensors", "capability.relativeHumidityMeasurement", title: "Select Humidity Sensors", submitOnChange: true, required: true, multiple: true
			paragraph "Enter weight factors and offsets"
			humidSensors.each {
				input "weight$it.id", "decimal", title: "$it ($it.currentHumidity)", defaultValue: 1.0, submitOnChange: true, width: 3
				input "offset$it.id", "decimal", title: "$it Offset", defaultValue: 0.0, submitOnChange: true, range: "*..*", width: 3
			}
			input "useRun", "number", title: "Compute running average over this many sensor events:", defaultValue: 1, submitOnChange: true
			input "lowClamp", "decimal", title: "Humidity clamp value low:", defaultValue: 30.0, required: true, submitOnChange: false
            input "highClamp", "decimal", title: "Humidity clamp value high:", defaultValue: 70.0, required: true, submitOnChange: false
            input "deltaClamp", "decimal", title: "Humidity clamp value delta:", defaultValue: 2.0, required: true, submitOnChange: false            

			if(humidSensors) paragraph "Current sensor average is ${averageHumid()}%"
			if(useRun > 1) {
				initRun()
				if(humidSensors) paragraph "Current running average is ${averageHumid(useRun)}%"
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
	def averageDev = getChildDevice("AverageHumid_${app.id}")
	if(!averageDev) averageDev = addChildDevice("hubitat", "Virtual Humidity Sensor", "AverageHumid_${app.id}", null, [label: thisName, name: thisName])
	averageDev.setHumidity(averageHumid())
	subscribe(humidSensors, "humidity", handler)
}

def initRun() {
	def humid = averageHumid()
//	if(!state.run) {
		state.run = []
		for(int i = 0; i < useRun; i++) state.run += humid
//	}
//    log.info "initRun: state.run = ${state.run}"
}

def averageHumid(run = 1) {
	def total = 0
	def n = 0
	def averageDev = getChildDevice("AverageHumid_${app.id}")
	def currentHumid = averageDev.currentValue("humidity")
	if (!lowClamp) lowClamp = 0.0
    if (!highClamp) highClamp = 100.0
	humidSensors.each {
        if (it.currentHumidity <=lowClamp || it.currentHumidity >= highClamp) log.warn "Humidity sensor ${it.label} reading ${it.currentHumidity} out of clamp range ${lowClamp}-${highClamp}"
		def offset = settings["offset$it.id"] != null ? settings["offset$it.id"] : 0
		total += (it.currentHumidity + offset) * (settings["weight$it.id"] != null ? settings["weight$it.id"] : 1)
		n += settings["weight$it.id"] != null ? settings["weight$it.id"] : 1
	}
	def result = total / (n = 0 ? humidSensors.size() : n)
	if(run > 1) {
		total = 0
		state.run.each {total += it}
		result = total / run
	}
    def deltaResult = Math.abs(currentHumid - result)
	if ((result > lowClamp) && (result < highClamp) && (deltaResult < deltaClamp)) {
        state.clamped = false
        return result.toDouble().round(1)
    } else {
        state.clamped = true
        log.warn "Humidity deltaResult=${deltaResult}"
        log.warn "Humidity average clamped: extraneous calculated value ${result}"
        return currentHumid
    }
    
    state.clamped = true
    if (result < lowClamp) {
        log.warn "Humidity average clamped: calculated value ${result} below ${lowClamp}"
        return currentHumid
    } else if (result > highClamp) {
        log.warn "Humidity average clamped: calculated value ${result} above ${highClamp}"
        return currentHumid
    } else if (Math.abs(currentHumid - result) > deltaClamp) {
        log.warn "Humidity average clamped: calculated value ${result} changed more than ${deltaClamp}"
        return currentHumid
    } else {
        state.clamped = false
        return result.toDouble().round(1)
    }
}

def handler(evt) {
	def averageDev = getChildDevice("AverageHumid_${app.id}")
	def avg = averageHumid()
    def avgStr = ""
	if(useRun > 1) {
        //log.info "Event change before: state.run = ${state.run}"
		state.run = state.run.drop(1) + avg
        //log.info "Event change after: state.run = ${state.run}"
		avg = averageHumid(useRun)
	}
	averageDev.setHumidity(avg)
    avgStr = String.format("%.1f", avg) + "%"
    avgStr = avgStr.trim()
	//log.info "Average sensor humidity = ${averageHumid()}°" + (useRun > 1 ? "    Running average is $avg%" : "")
    state.clamped ? updateAppLabel("Clamped", "orange", "${avgStr},") : updateAppLabel("${avgStr}", "green")
}

def updateAppLabel(textStr, textColor, textPrefix = '') {
  def str = (textPrefix = null) ? """<span style='color:$textColor'> ($textPrefix $textStr)</span>""" : """<span style='color:$textColor'> ($textStr)</span>"""
    app.updateLabel(thisName + str)
}
