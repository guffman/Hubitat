/**
 *  Sony Audio Control Beta
 *  Version=1.0.6
 *  Hubitat Integration
 *  Utilized the below URL for commands and values
 *  https://developer.sony.com/develop/audio-control-api/api-references/api-overview-2
 *  Driver was devleloped and tested for a sony CT800, certain commands may not work for other items
 *  STR-DN1080, SRS-ZR5, HT-Z9F, HT-MT500, HT-ST5000 are the listed devices, but almost any sony networked audio device should work with little modification
 *  Device capability matrix is in the URL below.
 *  https://developer.sony.com/develop/audio-control-api/api-references/device-uri
 *  There are many hidden methods that are not on sony's audio API documents, some are borrowed on from their TV API URL below
 *  https://pro-bravia.sony.net/develop/integrate/rest-api/spec/index.html
 *  Certain products may need to have their method versions updated depending on the specfic product (a newer soundbar may have 1.1 instead of 1.0) 
 *  IMPORT URL: https://raw.githubusercontent.com/jonesalexr/hubitat/master/Drivers/SonyAudio/SonyAudioControl.groovy
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
 */
 metadata {
  definition (name: "Sony Audio Control Beta", namespace: "ajones", author: "Alex Jones") {
    capability "Switch"
    capability "Refresh"
    capability "Polling"
    capability "AudioVolume"
    //capability "MusicPlayer"
    command "setSubLevel", ["number"]
    command "setNightModeOn"
    command "setNightModeOff"
    command "setSoundField", [[name:"Choose Soundfield", type: "ENUM", constraints: [
				"","clearAudio","movie","music","sports","game","standard","off"] ] ]
    //Enable below command if you want to return json data for debugging. This might be used to see which methods your device supports or to test a post call.            
    command "sendDebugString",[[name:"libpath",type:"STRING", description:"path to lib, '/sony/system'", constraints:["STRING"]],
    [name:"jsonmsg",type:"JSON_OBJECT", description:"json msg for post", constraints:["JSON_OBJECT"]]
    ]
    command "InputSelect", [[name:"Choose Input", type: "ENUM", constraints: [
				"",
                "HDMI1",
                "HDMI2",
                "HDMI3",
                "TV"
                ] ] ]
    command "getInfo"
    command "getCapability"
    command "clearCapability"
    attribute "SubLevel", "number"
    attribute "NightMode", "string"
    attribute "SoundField", "string"
    attribute "CurrentInput", "string"
    attribute "ZZJSONReturn", "string"
    attribute "SupportedAPI", "string"
    }

preferences {
        input("ipAddress", "string", title:"Sony IP Address", required:true, displayDuringSetup:true)
        input("ipPort", "string", title:"Sony Port (default: 100000)", defaultValue:10000, required:true, displayDuringSetup:true)
        input("PSK", "string", title:"PSK Passphrase", defaultValue:"", required:false, displayDuringSetup:true)
        input("WOLEnable", "bool", title:"Send WOL Packet when off", defaultValue:false)
        input("refreshInterval", "enum", title: "Refresh Interval in minutes", defaultValue: "10", required:true, displayDuringSetup:true, options: ["1","5","10","15","30"])
        input("logEnable", "bool", title: "Enable debug logging", defaultValue: true)
    }
 }

 // Utility Functions-------------------------------------------------------------------------------------------------------------------------------------------------------------------------

//Below function will run the refresh task according the schedule set in preferences
 private startScheduledRefresh() {
    if (logEnable) log.debug "startScheduledRefresh()"
    // Get minutes from settings
    def minutes = settings.refreshInterval?.toInteger()
    if (!minutes) {
        log.warn "Using default refresh interval: 10"
        minutes = 10
    }
    if (logEnable) log.debug "Scheduling polling task for every '${minutes}' minutes"
    if (minutes == 1){
        runEvery1Minute(refresh)
    } else {
        "runEvery${minutes}Minutes"(refresh)
    }
    runEvery30Minutes(getInfo)
}

//Below function will take place anytime the save button is pressed on the driver page
def updated() {
    log.warn "Updated with settings: ${settings}"
    // Prevent function from running twice on save
    if (!state.updated || now() >= state.updated + 5000){
        // Unschedule existing tasks
        unschedule()
        // Any additional tasks here
        // Start scheduled task
        startScheduledRefresh()
    }
    state.updated = now()
    state.driverversion = "1.0.6"
    if (logEnable) runIn(3600,logsOff)
    refresh()
    getInfo()
}

//Below function will disable debugs logs after 3600 seconds called in the updated function
def logsOff(){
    log.warn "debug logging disabled..."
    device.updateSetting("logEnable",[value:"false",type:"bool"])
}

//Below function will send magic packet to the device ID
def WOLC() {
    if (logEnable) log.debug "Executing Wake on Lan"
	def result = new hubitat.device.HubAction (
  	  	"wake on lan ${state.macAddr}", 
   		hubitat.device.Protocol.LAN,
   		null,
    	[secureCode: "111122223333"]
	)
	return result
}

//Below function is the API call to the host machine. This uses httpPostJSON and returns the response already parsed by HttpResponseDecorator
private postAPICall(lib,json) {
	def headers = [:]
        headers.put("HOST", "${settings.ipAddress}:${settings.ipPort}")
        //headers.put("Content-Type", "application/json")
        headers.put("X-Auth-PSK", "${settings.PSK}")
    
    def requestParams = [
		uri:  "http://${settings.ipAddress}:${settings.ipPort}" ,
        path: lib,
        headers: headers,
//requestContentType: "application/json",
		query: null,
		body: json,
	]
    if (logEnable) log.debug "${requestParams}"
	httpPostJson(requestParams) { response ->
		def msg = ""
		if (response?.status == 200) {
			msg = "Success"
		}
		else {
			msg = "${response?.status}"
		}
		if (logEnable) log.debug "Sony Response: ${msg} (${response.data})"

        if (response.data.id != 999){
            jsonreturnaction(response)
	    }
        if (response.data.id == 999){
            jsonreturnaction(response)
        }
    }
}



//Below function will take action on the response message from the API Post Call
private jsonreturnaction(response){
    if (logEnable) log.debug "ID is ${response.data.id}"
    if (logEnable) log.debug "raw data result is ${response.data.result}"

    String responsedataerror = response.data.error
    if (logEnable) log.debug "dataerrorstring is ${responsedataerror}"

    if (responsedataerror != null){
    log.warn "data error is ${response.data.error}"
    }

  if (response.data?.id == 2) {
  	//Set the Global value of state.device on or off
    if (logEnable) log.debug "Status is ${response.data.result[0]?.status}"
    def devicestate = (response.data.result[0]?.status == "active") ? "on" : "off"
    sendEvent(name: "switch", value: devicestate, isStateChange: true)
    if (logEnable) log.debug "DeviceState Event is '${devicestate}'"
  }

  if (response.data?.id == 50) {
  	//Set the Global value of state.devicevolume
    if (logEnable) log.debug "Volume is ${response.data.result[0][0]?.volume}"
    def devicevolume = response.data.result[0][0]?.volume
       sendEvent(name: "volume", value: devicevolume, isStateChange: true)
    if (logEnable) log.debug "DeviceVolume Event is '${devicevolume}'"
  }

  if (response.data?.id == 55) {
  	//Set the Global value of state.sublevel
    if (logEnable) log.debug "SubLevel is ${response.data.result[0][0]?.currentValue}"
    def sublevel = response.data.result[0][0]?.currentValue
    sendEvent(name: "SubLevel", value: sublevel, isStateChange: true)
    if (logEnable) log.debug "Sublevel Event is '${sublevel}'"
  }
  if (response.data?.id == 40) {
  	//Set the Global value of state.devicemute
    if (logEnable) log.debug "Mute is ${response.data.result[0][0]?.mute}"
    def devicemute = response.data.result[0][0]?.mute
    sendEvent(name: "mute", value: devicemute, isStateChange: true)
    if (logEnable) log.debug "Devicemute State is '${devicemute}'"
  }
  if (response.data?.id == 99) {
  	//Set the Global value of systeminfo
    if (logEnable) log.debug "bdAddr State is ${response.data.result[0]?.bdAddr}"
    state.bdAddr = response.data.result[0]?.bdAddr
    if (logEnable) log.debug "macAddr State is ${response.data.result[0]?.macAddr}"
    state.macAddr = response.data.result[0]?.macAddr
    if (logEnable) log.debug "version is State ${response.data.result[0]?.version}"
    state.version = response.data.result[0]?.version
    if (logEnable) log.debug "wirelessMacAddr State is ${response.data.result[0]?.wirelessMacAddr}"
    state.wirelessMacAddr = response.data.result[0]?.wirelessMacAddr
  }
  if (response.data?.id == 98) {
  	//Set the Global value of interfaceinfo
    if (logEnable) log.debug "interfaceVersion State is ${response.data.result[0]?.interfaceVersion}"
    state.interfaceVersion = response.data.result[0]?.interfaceVersion
    if (logEnable) log.debug "modelName State is ${response.data.result[0]?.modelName}"
    state.modelName = response.data.result[0]?.modelName
    if (logEnable) log.debug "productCategory is State ${response.data.result[0]?.productCategory}"
    state.productCategory = response.data.result[0]?.productCategory
    if (logEnable) log.debug "productName State is ${response.data.result[0]?.productName}"
    state.productName = response.data.result[0]?.productName
    if (logEnable) log.debug "serverName State is ${response.data.result[0]?.serverName}"
    state.serverName = response.data.result[0]?.serverName
  }
  if (response.data?.id == 97) {
  	//Set the Global value of miscsettings
    if (logEnable) log.debug "devicename State is ${response.data.result[0]?.currentValue}"
    state.devicename = response.data.result[0]?.currentValue
  }
  if (response.data?.id == 61) {
  	//Set the Global value of state.nightmode
    if (logEnable) log.debug "nightmode is ${response.data.result[0][0]?.currentValue}"
    def nightmode = response.data.result[0][0]?.currentValue
    sendEvent(name: "NightMode", value: nightmode, isStateChange: true)
    if (logEnable) log.debug "NightMode event is '${nightmode}'"
  }
    if (response.data?.id == 65) {
  	//Set the Global value of state.nightmode
    if (logEnable) log.debug "soundfield is ${response.data.result[0][0]?.currentValue}"
    def soundfield = response.data.result[0][0]?.currentValue
    sendEvent(name: "SoundField", value: soundfield, isStateChange: true)
    if (logEnable) log.debug "SoundField event is '${soundfield}'"
  }
    if (response.data?.id == 70) {
  	//Set the Global value of state.currentinput
    if (logEnable) log.debug "currentinput is ${response.data.result[0][0]?.uri}"
    def currentinput = response.data.result[0][0]?.uri
    sendEvent(name: "CurrentInput", value: currentinput, isStateChange: true)
    if (logEnable) log.debug "CurrentInput State is '${currentinput}'"
    
  }
    if (response.data?.id == 998) {
  	//Set the Global value of state.SupportedAPIs
    if (logEnable) log.debug "SupportedAPIs is ${response.data}"
    def sprtapirespX = response.data
    sendEvent(name: "SupportedAPI", value: sprtapirespX, isStateChange: true)

    def theRes = response?.data?.result?.get(0)?.find { it.service == "system" }
    log.debug "theRes = ${theRes}"
    def theApi = theRes?.apis?.find { it.name == "getPowerStatus" }
    log.debug "theApi = ${theApi}"
    for(thisVers in theApi?.versions) {log.debug "${thisVers?.version}"}

    
  }
    if (response.data?.id == 999) {
  	//Set the Global value of state.currentinput
    if (logEnable) log.debug "parsestring result is ${response.data}"
        //state.zzdebugjsonstate = response.data
       def debugjson = response.data
         sendEvent(name: "ZZJSONReturn", value: debugjson, isStateChange: true)
  }
    else {if (logEnable) log.debug "no id found for result action"}

}

//Button Commands  ------------------------------------------------------------------------------------------------------------------


//Switch Capability+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
def on(){
    if (logEnable) log.debug "on pushed"
    if (WOLEnable) WOLC()
    setPowerStatusOn()
}

def off(){
    if (logEnable) log.debug "off pushed"
    setPowerStatusOff()
}

def poll() {
    if (logEnable) log.debug "Executing poll(), unscheduling existing"
    refresh()
}

def refresh() {
    if (logEnable) log.debug "Refreshing"
    getPowerStatus()
    getSoundVolume()
    getSubLevel()
    getMuteStatus()
    getNightModeStatus()
    getSoundField()
    getCurrentSource()
}

def getInfo(){
    getInterfaceInfo()
    getDeviceMiscSettings()
    getSystemInfo()
    getPowerSettings()
    clearCapability()

}

def getCapability(){
    getSupportedAPIInfo()
}


def clearCapability(){
    if (logEnable) log.debug "clearCapability clicked"
    if (logEnable) log.debug "remove attribute ZZJSONReturn"
    sendEvent(name: "ZZJSONReturn", value: "null", isStateChange: true)
    if (logEnable) log.debug "remove attribute SupportedAPI"
    sendEvent(name: "SupportedAPI", value: "null", isStateChange: true)
}


//SetInput++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
def InputSelect(def inputname){
    if (logEnable) log.debug "InputSelect Pressed with ${inputname}"
def input = null
    if (inputname == "HDMI1") { input = "extInput:hdmi?port=1"}
    if (inputname == "HDMI2") { input = "extInput:hdmi?port=2"}
    if (inputname == "HDMI3") { input = "extInput:hdmi?port=3"}
    if (inputname == "TV") { input = "extInput:tv"}

    SetInput(input)

}

def SetInput(input){
    if (logEnable) log.debug "Executing 'SetInput' "
    def lib = "/sony/avContent"
    def json = "{\"method\":\"setPlayContent\",\"id\":101,\"params\":[{\"uri\":\"${input}\"}],\"version\":\"1.2\"}"
    postAPICall(lib,json)
}

//AudioVolume Capability++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
def setVolume(level) {
    if (logEnable) log.debug "set volume pushed with ${level}"
    setSoundVolume(level)
}

def volumeUp() {
    if (logEnable) log.debug "volumeup pushed"
    def level="+1"
    setSoundVolume(level)
}

def volumeDown() {
    if (logEnable) log.debug "volumeup pushed"
    def level="-1"
    setSoundVolume(level)
}

def mute(){
    if (logEnable) log.debug "mute pushed"
    setMute()
}

def unmute(){
    if (logEnable) log.debug "unmute pushed"
    setUnMute()
}

def  nextTrack(){
    //todo
    if (logEnable) log.debug "nextTrack pushed"
}

def pause(){
    //todo
    if (logEnable) log.debug "pause pushed"
}

def play(){
    //todo
    if (logEnable) log.debug "play pushed"
}

def playtext(text){
    //todo
    if (logEnable) log.debug "nextTrack pushed"
}

def playTrack(trackuri){
    //todo
    if (logEnable) log.debug "nextTrack pushed"
}

def previousTrack(){
    //todo
    if (logEnable) log.debug "nextTrack pushed"
}

def restoreTrack(trackuri){
    //todo
    if (logEnable) log.debug "nextTrack pushed"
}

def resumeTrack(trackuri){
    //todo
    if (logEnable) log.debug "nextTrack pushed"
}

def setLevel(volumelevel){
    //todo
    if (logEnable) log.debug "nextTrack pushed"
}

def setTrack(trackuri){
    //todo
    if (logEnable) log.debug "nextTrack pushed"
}

def stop(){
    //todo
    if (logEnable) log.debug "nextTrack pushed"
}



//API Commands------------------------------------------------------------------------------------------------------------------------------------------------------------------------

def getPowerStatus() {
    if (logEnable) log.debug "Executing 'getPowerStatus' "
    def lib = "/sony/system"
    def json = "{\"id\":2,\"method\":\"getPowerStatus\",\"version\":\"1.1\",\"params\":[]}"
    postAPICall(lib,json)
}

def setPowerStatusOn() {
    if (logEnable) log.debug "Executing 'setPowerStatusOn' "
    def lib = "/sony/system"
    def json = "{\"method\":\"setPowerStatus\",\"version\":\"1.1\",\"params\":[{\"status\":\"active\"}],\"id\":3}"
    postAPICall(lib,json)
    pauseExecution(2000)
    getPowerStatus()
}

def setPowerStatusOff() {
    if (logEnable) log.debug "Executing 'setPowerStatusOff' "
    def lib = "/sony/system"
    def json = "{\"method\":\"setPowerStatus\",\"version\":\"1.1\",\"params\":[{\"status\":\"off\"}],\"id\":4}"
    postAPICall(lib,json)
    pauseExecution(2000)
    getPowerStatus()
}

def getSoundVolume() {
if (logEnable) log.debug "Executing 'getSoundVolume' "
    def lib = "/sony/audio"
    def json = "{\"method\":\"getVolumeInformation\",\"version\":\"1.1\",\"params\":[{\"output\":\"\"}],\"id\":50}"
    postAPICall(lib,json)
}

def setSoundVolume(def Level) {
    if (logEnable) log.debug "Executing 'setSoundVolume' with ${level} "
    def lib = "/sony/audio"
    def json = "{\"method\":\"setAudioVolume\",\"version\":\"1.1\",\"params\":[{\"volume\":\"${Level}\",\"output\":\"\"}],\"id\":51}"
    postAPICall(lib,json)
    getSoundVolume()
}

def getSubLevel() {
  if (logEnable) log.debug "Executing 'getSubLevel' "
    def lib = "/sony/audio"
    def json = "{\"method\":\"getSoundSettings\",\"version\":\"1.1\",\"params\":[{\"target\":\"subwooferLevel\"}],\"id\":55}"
    postAPICall(lib,json)
}

def setSubLevel(def Level) {
  if (logEnable) log.debug "Executing 'setSubLevel' with ${Level}"
    def lib = "/sony/audio"
    def json = "{\"method\":\"setSoundSettings\",\"version\":\"1.1\",\"params\":[{\"settings\":[{\"value\":\"${Level}\",\"target\":\"subwooferLevel\"}]}],\"id\":56}"
    postAPICall(lib,json)
    getSubLevel()
}

def getMuteStatus(){
    if (logEnable) log.debug "Executing 'getMuteStatus' "
    def lib = "/sony/audio"
    def json = "{\"method\":\"getVolumeInformation\",\"version\":\"1.1\",\"params\":[{\"output\":\"\"}],\"id\":40}"
    postAPICall(lib,json)
}

def setMute(){
    if (logEnable) log.debug "Executing 'setMute' "
    def lib = "/sony/audio"
    def json = "{\"method\":\"setAudioMute\",\"id\":41,\"params\":[{\"mute\":\"on\"}],\"version\":\"1.1\"}"
    postAPICall(lib,json)
    pauseExecution(2000)
    getMuteStatus()
}

def setUnMute(){
    if (logEnable) log.debug "Executing 'setUnMute' "
    def lib = "/sony/audio"
    def json = "{\"method\":\"setAudioMute\",\"id\":42,\"params\":[{\"mute\":\"off\"}],\"version\":\"1.1\"}"
    postAPICall(lib,json)
    pauseExecution(2000)
    getMuteStatus()
}

def getSystemInfo(){
    if (logEnable) log.debug "Executing 'getSystemInfo' "
    def lib = "/sony/system"
    def json = "{\"method\":\"getSystemInformation\",\"id\":99,\"params\":[],\"version\":\"1.4\"}"
    postAPICall(lib,json)
}

def getNightModeStatus(){
    if (logEnable) log.debug "Executing 'getNightModeStatus' "
    def lib = "/sony/audio"
    def json = "{\"method\":\"getSoundSettings\",\"id\":61,\"params\":[{\"target\":\"nightMode\"}],\"version\":\"1.1\"}"
    postAPICall(lib,json)
}

def setNightModeOn(){
    if (logEnable) log.debug "Executing 'setNightModeOn' "
    def lib = "/sony/audio"
    def json = "{\"method\":\"setSoundSettings\",\"id\":62,\"params\":[{\"settings\":[{\"value\":\"on\",\"target\":\"nightMode\"}]}],\"version\":\"1.1\"}"
    postAPICall(lib,json)
        pauseExecution(2000)
    getNightModeStatus()
}

def setNightModeOff(){
    if (logEnable) log.debug "Executing 'setNightModeOff' "
    def lib = "/sony/audio"
    def json = "{\"method\":\"setSoundSettings\",\"id\":63,\"params\":[{\"settings\":[{\"value\":\"off\",\"target\":\"nightMode\"}]}],\"version\":\"1.1\"}"
    postAPICall(lib,json)
            pauseExecution(2000)
    getNightModeStatus()
}

def getSoundField(){
    if (logEnable) log.debug "Executing 'getSoundField' "
    def lib = "/sony/audio"
    def json = "{\"method\":\"getSoundSettings\",\"id\":65,\"params\":[{\"target\":\"soundField\"}],\"version\":\"1.1\"}"
    postAPICall(lib,json)
}

def setSoundField(def mode){
    if (logEnable) log.debug "Executing 'setSoundField' "
    if (logEnable) log.debug "variable is ${mode}"
    def lib = "/sony/audio"
    def json = "{\"method\":\"setSoundSettings\",\"id\":66,\"params\":[{\"settings\":[{\"value\":\"${mode}\",\"target\":\"soundField\"}]}],\"version\":\"1.1\"}"
    postAPICall(lib,json)
        pauseExecution(2000)
    getSoundField()
}

def getInterfaceInfo(){
    if (logEnable) log.debug "Executing 'getInterfaceInfo' "
    def lib = "/sony/system"
    def json = "{\"method\":\"getInterfaceInformation\",\"id\":98,\"params\":[],\"version\":\"1.0\"}"
    postAPICall(lib,json)
}

def getDeviceMiscSettings(){
    if (logEnable) log.debug "Executing 'getMiscSettings' "
    def lib = "/sony/system"
    def json = "{\"method\":\"getDeviceMiscSettings\",\"id\":97,\"params\":[{\"target\":\"deviceName\"}],\"version\":\"1.0\"}"
    postAPICall(lib,json)
}

def getPowerSettings(){
    if (logEnable) log.debug "Executing 'getPowerSettings' "
    def lib = "/sony/system"
    def json = "{\"method\":\"getPowerSettings\",\"id\":96,\"params\":[{\"output\":\"\"}],\"version\":\"1.0\"}"
    postAPICall(lib,json)
}

def sendDebugString(libpath,jsonmsg){
    //add ID of 999 to test PARSE message
    if (logEnable) log.debug "Executing 'sendDebugString' "
    state.zzdebugjsonlibpath = libpath
    state.zzdebugjsonmsg = jsonmsg
    def lib = libpath
    def json = jsonmsg
    postAPICall(lib,json)
}

def getCurrentSource(){
        if (logEnable) log.debug "Executing 'getCurrentSource' "
    def lib = "/sony/avContent"
    def json = "{\"method\":\"getPlayingContentInfo\",\"id\":70,\"params\":[{\"output\":\"\"}],\"version\":\"1.2\"}"
    postAPICall(lib,json)
}

def getSupportedAPIInfo(){
        if (logEnable) log.debug "Executing 'getSupportedAPIInfo' "
    def lib = "/sony/guide"
    def json = "{\"method\":\"getSupportedApiInfo\",\"id\":998,\"params\":[{\"services\":[\"system\",\"avContent\",\"guide\",\"appControl\",\"audio\",\"videoScreen\"]}],\"version\":\"1.0\"}"
    postAPICall(lib,json)
}
