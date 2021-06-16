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
			input "thisName", "text", title: "Name this humidity averager", defaultValue: "Humidity Averager", submitOnChange: true
            if(thisName) {
                app.updateLabel("$thisName")
                state.label = thisName
            }
			input "humidSensors", "capability.relativeHumidityMeasurement", title: "Select Humidity Sensors", submitOnChange: true, required: true, multiple: true
			paragraph "Enter weight factors and offsets"
			humidSensors.each {
				input "weight$it.id", "decimal", title: "$it ($it.currentHumidity)", defaultValue: 1.0, submitOnChange: true, width: 3
				input "offset$it.id", "decimal", title: "$it Offset", defaultValue: 0.0, submitOnChange: true, range: "*..*", width: 3
			}
			input "useRun", "number", title: "Compute running average over this many sensor events:", defaultValue: 1, submitOnChange: true
			input "lowClamp", "decimal", title: "Humidity sensor clamp value low:", defaultValue: 30.0, required: true, submitOnChange: false
            input "highClamp", "decimal", title: "Humidity sensor clamp value high:", defaultValue: 70.0, required: true, submitOnChange: false
            input "deltaClamp", "decimal", title: "Humidity sensor clamp value delta:", defaultValue: 2.0, required: true, submitOnChange: false            

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
    state.label = thisName
}

def initRun() {
	def humid = averageHumid()
	state.run = []
	for(int i = 0; i < useRun; i++) state.run += humid
}

def averageHumid(run = 1) {
	def total = 0
	def n = 0
    def avg = 0.0
	def averageDev = getChildDevice("AverageHumid_${app.id}")
	def currentHumid = averageDev.currentValue("humidity")
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
    
    // Sensor average validity tests
    avg = result.toDouble().round(1)
    avgStr = String.format("%.1f", avg) + "%"
    avgStr = avgStr.trim()

	if ((result > lowClamp) && (result < highClamp) && (deltaResult < deltaClamp)) {
        state.clamped = false
        updateAppLabel(avgStr, "green")
        return avg
    }
    
    state.clamped = true
    if (result <= lowClamp) {
        log.warn "Humidity average clamped: calculated value ${avgStr} below ${lowClamp}"
        updateAppLabel("Below Low Limit - Clamped", "orange", avgStr)
    } else if (result >= highClamp) {
        log.warn "Humidity average clamped: calculated value ${avgStr} above ${highClamp}"
        updateAppLabel("Above High Limit - Clamped", "orange", avgStr)
    } else if (Math.abs(currentHumid - result) >= deltaClamp) {
        log.warn "Humidity average clamped: calculated value ${avgStr} changed more than ${deltaClamp}"
        updateAppLabel("Above Delta Limit - Clamped", "orange", avgStr)
    }
    return currentHumid
}

def handler(evt) {
	def averageDev = getChildDevice("AverageHumid_${app.id}")
	def avg = averageHumid()
	if(useRun > 1) {
		state.run = state.run.drop(1) + avg
		avg = averageHumid(useRun)
	}
	averageDev.setHumidity(avg)
}

def updateAppLabel(textStr, textColor, def textPrefix=null) {
    def str = (textPrefix != null) ? """<span style='color:$textColor'> ($textPrefix $textStr)</span>""" : """<span style='color:$textColor'> ($textStr)</span>"""
    app.updateLabel(state.label + str)
}
