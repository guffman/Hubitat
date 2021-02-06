/**
* 
*
*  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License. You may obtain a copy of the License at:
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
*  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
*  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
*  for the specific language governing permissions and limitations under the License.
*
*  Virtual Air Quality Station
*
*  Author: Guffman
*. Credit to BPTWorld Weather.gov data driver for some code segments and structure
*
*  Date: 2020-09-17
*
*
*	1.0.0 - Initial Release
*   1.1.0 - Added direct http polling of caqi.info
*   1.2.0 - Added error checking features to address variation in WAQI responses
*
*/

def getVersionNum() { return "1.2.0" }
private def getVersionLabel() { return "Virtual Air Quality Station, version ${getVersionNum()}" }

metadata {
    definition (name: "Virtual Air Quality Station", namespace: "Guffman", author: "Guffman", importUrl: "") {
        
        capability "Sensor"
        capability "Refresh"
        
        attribute "stationName", "string"
        attribute "stationId", "number"
        attribute "stationActive", "number"    // 1=active, 0=stale
        attribute "aqi", "number"				// current AQI
        attribute "aqiCategory", "number"       // 1-6, breakpoints defined by EPA spec
        attribute "aqiCategoryLabel", "string"  // AQI category friendly name
		attribute "co", "number"				// ppm
		attribute "no2", "number"				// ppb
		attribute "o3", "number"				// ppb
		attribute "pm10", "number"				// ug/m3
		attribute "pm2_5", "number"				// ug/m3
		attribute "so2", "number"				// ppb
        attribute "timestamp", "string"         // iso format
        attribute "pollCounter", "number"       // simple counter of requests
        attribute "webApiStatus", "number"      // http response
        
    }

    preferences {
        input(name: 'station', type: 'string', title: "Air Quality Station Identifier", displayDuringSetup: true, defaultValue: "louisiana/kenner-site", required: true)
        input(name: 'token', type: 'string', title: "API Token", displayDuringSetup: true, defaultValue: "ac851c82d0ae8b5ff1144302748927a04ba8ab38", required: true)
        input (name: "pollInterval", type: "enum", title: "Poll Interval", defaultValue: 15, options:[5:"5 min",10:"10 min",15:"15 min",30:"30 min",60:"1 hr"], required: true)
		input(name: 'logEnable', type: 'bool', title: "Enable debug logging?", displayDuringSetup: true, defaultValue: false)
    }
}

def installed() {
	initialize()
}

def updated() {
	log.info "Updated with settings: ${settings}"
    initialize()
    getAirQualityData()
}

def initialize() {
    unschedule()
    if (state.pollCount == null) state.pollCount = 0
    interval = pollInterval.toInteger()
    switch (interval) {
        case 5:
            runEvery5Minutes(getAirQualityData)
            break
        case 10:
            runEvery10Minutes(getAirQualityData)
            break
        case 15:
            runEvery15Minutes(getAirQualityData)
            break
        case 30:
            runEvery30Minutes(getAirQualityData)
            break
        case 60:
            runEvery1Hour(getAirQualityData)
            break
    }
}

def refresh() {
    getAirQualityData()
}

def getAirQualityData() {
    if(logEnable) log.debug "In getAirQualityData"
    
    if(state.attempts == null) state.attempts = 1
    waqiURL = "https://api.waqi.info/feed/usa/${station}/?token=${token}"
    
	if(logEnable) log.debug "In getAirQualityData - URL: ${waqiURL}"
	def requestParams =
		[
			uri: waqiURL,
            requestContentType: "application/json",
			contentType: "application/json",
            timeout: 15,
		]
    try {
        httpGet(requestParams) { response ->
            if(logEnable) log.debug "In getAirQualityData - response status: ${response.status}"
            
            if(response.status == 200) {
                def data = response.data.data
                if (logEnable) log.debug "In getAirQualityData - response data: ${data}"
                setStationName(data.city.name)
                setStationId(data.idx)
                setAqi(data.aqi)
                
                // Sometimes the response is missing an element or two, so need to check for existence of each
                
                (data.iaqi.containsKey('co')) ? setCO(data.iaqi.co.v) : log.warn("${device.getLabel()} : api.waqi response missing CO current value")
                (data.iaqi.containsKey('o3')) ? setO3(data.iaqi.o3.v) : log.warn("${device.getLabel()} : api.waqi response missing O3 current value")
                (data.iaqi.containsKey('no2')) ? setNO2(data.iaqi.no2.v) : log.warn("${device.getLabel()} : api.waqi response missing NO2 current value")
                (data.iaqi.containsKey('pm10')) ? setPM10(data.iaqi.pm10.v) : log.warn("${device.getLabel()} : api.waqi response missing PM10 current value")
                (data.iaqi.containsKey('pm25')) ? setPM2_5(data.iaqi.pm25.v) : log.warn("${device.getLabel()} : api.waqi response missing PM2_5 current value")
                (data.iaqi.containsKey('so2')) ? setSO2(data.iaqi.so2.v) : log.warn("${device.getLabel()} : api.waqi response missing SO2 current value")
                
                setTimestamp(data.time.iso)
                setStationActive(data.time.s)
                setWebApiStatus(response.status)
                incrPollCounter()
                state.attempts = 0
            } else {
                if(state.attempts < 4) {
                    runIn(15, getAirQualityData)
                    state.attempts = state.attempts + 1
                    if(logEnable) log.debug "In getAirQualityData - Bad Request - Will try again in 15 seconds (${state.attempts})"
                } else {
                    setWebApiStatus(response.status)
                    incrPollCounter()
                    if(logEnable) log.debug "In getAirQualityData - Bad Request - ${response.status} - Something went wrong, please try again. (${state.attempts})"
                }
            }
        }
    } 
    
    catch (SocketTimeoutException e) {
		log.warn "Connection to api.waqi.info timed out."
	}
    
    catch (e) {
        if(state.attempts < 4) {
            runIn(15, getAirQualityData)
            state.attempts = state.attempts + 1
            if(logEnable) log.debug "In getAirQualityData - Bad Request - Will try again in 15 seconds (${state.attempts})"
        } else {
            if(logEnable) log.debug "In getAirQualityData - Bad Request - Something went wrong, please try again."
            log.warn "In getAirQualityData - Catch - Either WAQI website is having issues or Station and Token invalid (${state.attempts})"
            log.warn "In getAirQualityData - Catch error = ${e}"
            incrPollCounter()
        }
    }
}

def setStationName(name) {
    def val = name.toString()
    if (logEnable) log.debug "In setStationName - val=${val}"
    sendEvent([name:"stationName", value:val, descriptionText:"Air Quality station name"])
}

def setStationId(idx) {
    if (logEnable) log.debug "In setStationId - idx=${idx}"
    sendEvent([name:"stationId", value:idx, descriptionText:"Air Quality station ID"])
}

def setAqi(idx) {
    def val = idx.toInteger()
    if (logEnable) log.debug "In setAqi - val=${val}"
    sendEvent([name:"aqi", value:val, descriptionText:"Air Quality Index (AQI) is ${val}", unit:"ug/m3"])
    setAqiCategory(val)
}

def setAqiCategory(idx) {
    def val = idx.toInteger()
    def categ = (val<=50) ? 1 : ((val<=100) ? 2 : ((val<=150) ? 3 : ((val<=200) ? 4 : ((val<=300) ? 5 : 6))))
    if (logEnable) log.debug "In setAqiCategory - categ=${categ}"
    sendEvent([name:"aqiCategory", value:categ, descriptionText:"AQI Category is ${categ}"])  
    
    def names = ["Undefined", "Good", "Moderate", "Unhealthy for Sensitive Groups", "Unhealthy", "Very Unhealthy", "Hazardous"]
    lbl = names.get(categ)
    if (logEnable) log.debug "In setAqiCategory - lbl=${lbl}"
    sendEvent([name:"aqiCategoryLabel", value:lbl, descriptionText:"AQI Category label is ${lbl}"])
}

def setCO(val) {
    if (logEnable) log.debug "In setCO - val=${val}"
    sendEvent([name:"co", value:val, descriptionText:"Air quality index is ${val}", unit:"ppm"])
}

def setNO2(val) {
    if (logEnable) log.debug "In setNO2 - val=${val}"
    sendEvent([name:"no2", value:val, descriptionText:"NO2 concentration is ${val}", unit:"ppb"])
}

def setO3(val) {
    if (logEnable) log.debug "In setO3 - val=${val}"
    sendEvent([name:"o3", value:val, descriptionText:"O3 concentration is ${val}", unit:"ppb"])
}

def setPM10(val) {
    if (logEnable) log.debug "In setPM10 - val=${val}"
    sendEvent([name:"pm10", value:val, descriptionText:"Particulate Matter (PM10) is ${val}", unit:"ug/m3"])
}

def setPM2_5(val) {
    if (logEnable) log.debug "In setPM2_5 - val=${val}"
    sendEvent([name:"pm2_5", value:val, descriptionText:"Fine Particulate Matter (PM2.5) is ${val}", unit:"ug/m3"])
}

def setSO2(val) {
    if (logEnable) log.debug "In setSO2 - val=${val}"
    sendEvent([name:"so2", value:val, descriptionText:"SO2 concentration is ${val}", unit:"ppb"])
}

def setTimestamp(ts) {
    if (logEnable) log.debug "In setTimestamp - ts=${ts}"
    sendEvent([name:"timestamp", value:ts, descriptionText:"Sample taken at ${ts}"])
}

def setStationActive(ts) {
    if (logEnable) log.debug "In setStationActive - ts=${ts}" 
    // Compute delta time since last station update, set stationActive to result
    def timeNow = new Date()
    def timeSampled = Date.parse("yyyy-MM-dd HH:mm:ss", ts)  //2020-09-25 18:00:00
    def duration = groovy.time.TimeCategory.minus(timeNow, timeSampled)
    def timeDiff = (duration.hours * 3600) + (duration.minutes * 60) + duration.seconds
    def active = (timeDiff < 7200) ? 1 : 0
    def activeText = (active) ? "Air quality station reading is active" : "Air quality station reading is stale"
    if (logEnable) log.debug "In setStationActive - timeNow=${timeNow}, timeSampled=${timeSampled}, timeDiff=${timeDiff} sec, active=${active}, activeText=${activeText}"
    sendEvent([name:"stationActive", value:active, descriptionText: activeText])
}

def setWebApiStatus(val) {
    def code = val.toInteger()
    if (logEnable) log.debug "In setWebApiStatus - code=${code}"
    sendEvent([name:"webApiStatus", value:code, descriptionText:"api.waqi.info response code was ${code}"])
}

def incrPollCounter() {
    state.pollCount = state.pollCount + 1
    if (logEnable) log.debug "In incrPollCounter - pollCount=${state.pollCount}"
    sendEvent([name:"pollCounter", value:state.pollCount, descriptionText:"api.waqi.info poll counter is ${state.pollCount}"])
}
