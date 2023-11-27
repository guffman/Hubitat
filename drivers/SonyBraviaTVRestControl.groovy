/**
 *  Sony Bravia TV Rest Control
 *  Version=1.0.7
 *  Hubitat Integration
 *  Utilized the below URL for commands and values
 *  https://pro-bravia.sony.net/develop/integrate/rest-api/spec/index.html
 *  Driver was devleloped and tested for a sony XBR-65X850F, certain commands may not work for other items
 *  This driver should work for most Sony Bravia TV's, but apps, button commands may differ between different devices
 *  Certain products may need to have their method versions updated depending on the specfic product (a newer soundbar may have 1.1 instead of 1.0) 
 *  IMPORT URL: https://raw.githubusercontent.com/jonesalexr/hubitat/master/Drivers/SonyBravia/SonyBraviaTVRestControl.groovy
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
 *  Modified 11/27/2023 by Guffman - added a few more KeyPress codes to the input enum.
 *
 */
 metadata {
  definition (name: "Sony Bravia TV Rest Control", namespace: "ajones", author: "Alex Jones") {
    capability "Switch"
    capability "Refresh"
    capability "Polling"
    capability "AudioVolume"
    command "getInfo"
    command "Reboot"
    command "TerminateApps"
    command "SendURL", ["string"]
  command "LaunchApp", [[name:"ChooseApp", type: "ENUM", constraints: [
				"",
                "Disney+",
                "ESPN",
                "Hulu",
                "Netflix",
                "Plex",
                "Pluto TV",
                "Prime Video",
                "Program Guide",
                "Sling TV",
                "Spotify",
                "tinyCam PRO",
                "TV",
                "YouTube"
                ] ] ]
    command "InputSelect", [[name:"Choose Input", type: "ENUM", constraints: [
				"",
                "HDMI1",
                "HDMI2",
                "HDMI3",
                "HDMI4"
                ] ] ]
    //Enable below command if you want to return json data for debugging. This might be used to see which methods your device supports or to test a post call.            
    command "sendDebugString",[[name:"libpath",type:"STRING", description:"path to lib", constraints:["STRING"]],
    [name:"jsonmsg",type:"JSON_OBJECT", description:"json msg for post", constraints:["JSON_OBJECT"]]
    ]
    command "keyPress", [[name:"Key Press Action", type: "ENUM", constraints: [
                "",
                "Num0",
                "Num1",
                "Num2",
                "Num3",
                "Num4",
                "Num5",
                "Num6",
                "Num7",
                "Num8",
                "Num9",
                "DOT",
                "Enter",
                "ChannelUp",
                "ChannelDown",
                "VolumeUp",
                "VolumeDown",
                "Mute",
                "TvPower",
                "Tv",
                "WakeUp",
                "PowerOff",
                "Sleep",
                "Right",
                "Left",
                "SleepTimer",
                "Display",
                "Home",
                "Exit",
                "Up",
                "Down",
                "ClosedCaption",
                "Wide",
                "Stop",
                "Pause",
                "Play",
                "Rewind",
                "Forward",
                "Prev",
                "Next",
                "DpadCenter",
                "Hdmi1",
                "Hdmi2",
                "Hdmi3",
                "Hdmi4",
                "Netflix",
                "MuteOn",
                "MuteOff",
                "YouTube"
                ] ] ]
    attribute "CurrentInput", "string"
    attribute "Channel", "string"
    attribute "SpeakerOutput", "string"
    attribute "PowerSave", "string"
    attribute "Source", "string"
    attribute  "Title", "string"
    attribute "OriginalDisplayNumber", "string"
    attribute "ProgramTitle", "string"
    attribute "WakeOnLanEnabled", "string"

    }

preferences {
        input("ipAddress", "string", title:"Sony IP Address", required:true, displayDuringSetup:true)
        input("ipPort", "string", title:"Sony Port (default: 80)", defaultValue:80, required:true, displayDuringSetup:true)
        input("PSK", "string", title:"PSK Passphrase", defaultValue:"", required:false, displayDuringSetup:true)
        input("WOLEnable", "bool", title:"Send WOL Packet when off", defaultValue:false)
        input("refreshInterval", "enum", title: "Refresh Interval in minutes", defaultValue: "10", required:true, displayDuringSetup:true, options: ["1","5","10","15","30"])
        input("logEnable", "bool", title: "Enable debug logging for 1 hour", defaultValue: true)
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
    if (logEnable) log.info "raw data result is ${response.data.result}"

    String responsedataerror = response.data.error
    if (logEnable) log.warn "dataerrorstring is ${responsedataerror}"

    if (responsedataerror != null){
    if (logEnable) log.warn "data error is ${response.data.error}"
    }

  if (response.data?.id == 2) {
  	//Set the Global value of state.device on or off
    if (logEnable) log.debug "Status is ${response.data.result[0]?.status}"
    def devicestate = (response.data.result[0]?.status == "active") ? "on" : "off"
    state.devicepower = devicestate
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
  if (response.data?.id == 40) {
  	//Set the Global value of state.devicemute
    if (logEnable) log.debug "Mute is ${response.data.result[0][0]?.mute}"
    def devicemute = (response.data.result[0][0]?.mute == true) ? "on" : "off"
    sendEvent(name: "mute", value: devicemute, isStateChange: true)
    if (logEnable) log.debug "Devicemute State is '${devicemute}'"
  }
  if (response.data?.id == 73) {
  	//Set the Global value of speakeroutput
    if (logEnable) log.debug "Speaker output is ${response.data.result[0][0]?.currentValue}"
    def speakermode = response.data.result[0][0]?.currentValue
    sendEvent(name: "SpeakerOutput", value: speakermode, isStateChange: true)
    if (logEnable) log.debug "speakermode State is '${speakermode}'"
  }
  if (response.data?.id == 89) {
  	//Set the Global value of powersave
    if (logEnable) log.debug "Powersave Mode is ${response.data.result[0]?.mode}"
    def powersavemode = response.data.result[0]?.mode
    sendEvent(name: "PowerSave", value: powersavemode, isStateChange: true)
    if (logEnable) log.debug "Powersavemode is '${powersavemode}'"
  }
  if (response.data?.id == 86) {
  	//Set the Global value of powersave
    if (logEnable) log.debug "WOL Mode is ${response.data.result[0]?.enabled}"
    def wolmode = response.data.result[0]?.enabled
    sendEvent(name: "WakeOnLanEnabled", value: wolmode, isStateChange: true)
  }
  if (response.data?.id == 99) {
  	//Set the Global value of systeminfo
    if (logEnable) log.debug "macAddr State is ${response.data.result[0]?.macAddr}"
    state.macAddr = response.data.result[0]?.macAddr
    if (logEnable) log.debug "generation is State ${response.data.result[0]?.generation}"
    state.generation = response.data.result[0]?.generation
    if (logEnable) log.debug "serial State is ${response.data.result[0]?.serial}"
    state.serial = response.data.result[0]?.serial
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
  	//Set the Global value of EthernetSettings
    if (logEnable) log.debug "hwAddr State is ${response.data.result[0][0]?.hwAddr}"
    state.hwAddr = response.data.result[0][0]?.hwAddr
    if (logEnable) log.debug "ipAddrV4 State is ${response.data.result[0][0]?.ipAddrV4}"
    state.ipAddrV4 = response.data.result[0][0]?.ipAddrV4
    if (logEnable) log.debug "ipAddrV6 State is ${response.data.result[0][0]?.ipAddrV6}"
    state.ipAddrV6 = response.data.result[0][0]?.ipAddrV6
    if (logEnable) log.debug "gateway State is ${response.data.result[0][0]?.gateway}"
    state.gateway = response.data.result[0][0]?.gateway
    if (logEnable) log.debug "dns State is ${response.data.result[0][0]?.dns}"
    state.dns = response.data.result[0][0]?.dns
  }
    if (response.data?.id == 70) {
  	//Set the Global value of state.currentinput
        if (responsedataerror == null){

            if (logEnable) log.debug "currentinput is ${response.data.result[0]?.uri}"
            def currentinput = response.data.result[0]?.uri
            sendEvent(name: "CurrentInput", value: currentinput, isStateChange: true)

            if (logEnable) log.debug "source is ${response.data.result[0]?.source}"
            def inputsource = response.data.result[0]?.source
            sendEvent(name: "Source", value: inputsource, isStateChange: true)

            if (logEnable) log.debug "title State is ${response.data.result[0]?.title}"
            //state.title = response.data.result[0]?.title
            def inputtitle = response.data.result[0]?.title
            sendEvent(name: "Title", value: inputtitle, isStateChange: true)

            if (logEnable) log.debug "dispNum State is ${response.data.result[0]?.dispNum}"
            def dispNum = response.data.result[0]?.dispNum
            sendEvent(name: "Channel", value: dispNum, isStateChange: true)

            if (logEnable) log.debug "originalDispNum State is ${response.data.result[0]?.originalDispNum}"
            //state.originalDispNum = response.data.result[0]?.originalDispNum
            def origdisplaynum = response.data.result[0]?.originalDispNum
            sendEvent(name: "OriginalDisplayNumber", value: origdisplaynum, isStateChange: true)

            if (logEnable) log.debug "programTitle State is ${response.data.result[0]?.programTitle}"
            //state.programTitle = response.data.result[0]?.programTitle
            def progtitle = response.data.result[0]?.programTitle
            sendEvent(name: "ProgramTitle", value: progtitle, isStateChange: true)

        }
        if (responsedataerror != null){
            sendEvent(name: "CurrentInput", value: "SmartMode", isStateChange: true)
            sendEvent(name: "Channel", value: "SmartMode", isStateChange: true)
            sendEvent(name: "Source", value: "SmartMode", isStateChange: true)
            sendEvent(name: "Title", value: "SmartMode", isStateChange: true)
            sendEvent(name: "OriginalDisplayNumber", value: "SmartMode", isStateChange: true)
            sendEvent(name: "ProgramTitle", value: "SmartMode", isStateChange: true)

        }

  }
    if (response.data?.id == 999) {
  	//Set the Global value of state.currentinput
    if (logEnable) log.debug "parsestring result is ${response.data.result}"
  }
    else {if (logEnable) log.debug "no id found for result action"}

}

//Button Commands  ------------------------------------------------------------------------------------------------------------------

def getInfo(){
    if (logEnable) log.debug "getInfo pushed"
    if (state.devicepower == "on"){
    getSystemInfo()
    getInterfaceInfo()
    getEthernetSettings()
    getPowerSaveMode()
    getWOLMode()
    }
}

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
    if (state.devicepower == "on"){
        getSoundVolume()
        getMuteStatus()
        getCurrentSource()
        getSoundSettings()
}
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


//API Commands------------------------------------------------------------------------------------------------------------------------------------------------------------------------

def getPowerStatus() {
    if (logEnable) log.debug "Executing 'getPowerStatus' "
    def lib = "/sony/system"
    def json = "{\"id\":2,\"method\":\"getPowerStatus\",\"version\":\"1.0\",\"params\":[]}"
    postAPICall(lib,json)
}

def setPowerStatusOn() {
    if (logEnable) log.debug "Executing 'setPowerStatusOn' "
    def lib = "/sony/system"
    def json = "{\"method\":\"setPowerStatus\",\"version\":\"1.0\",\"params\":[{\"status\":true}],\"id\":3}"
    postAPICall(lib,json)
    pauseExecution(2000)
    getPowerStatus()
}

def setPowerStatusOff() {
    if (logEnable) log.debug "Executing 'setPowerStatusOff' "
    def lib = "/sony/system"
    def json = "{\"method\":\"setPowerStatus\",\"version\":\"1.0\",\"params\":[{\"status\":false}],\"id\":4}"
    postAPICall(lib,json)
    pauseExecution(2000)
    getPowerStatus()
}

def getSoundVolume() {
if (logEnable) log.debug "Executing 'getSoundVolume' "
    def lib = "/sony/audio"
    def json = "{\"method\":\"getVolumeInformation\",\"version\":\"1.0\",\"params\":[{\"output\":\"\"}],\"id\":50}"
    postAPICall(lib,json)
}

def setSoundVolume(def Level) {
    if (logEnable) log.debug "Executing 'setSoundVolume' with ${level} "
    def lib = "/sony/audio"
    def json = "{\"method\":\"setAudioVolume\",\"id\":51,\"params\":[{\"volume\":\"${Level}\",\"target\":\"\",\"ui\":\"on\"}],\"version\":\"1.2\"}"
    postAPICall(lib,json)
    getSoundVolume()
}

def getMuteStatus(){
    if (logEnable) log.debug "Executing 'getMuteStatus' "
    def lib = "/sony/audio"
    def json = "{\"method\":\"getVolumeInformation\",\"version\":\"1.0\",\"params\":[{\"output\":\"\"}],\"id\":40}"
    postAPICall(lib,json)
}

def setMute(){
    if (logEnable) log.debug "Executing 'setMute' "
    def lib = "/sony/audio"
    def json = "{\"method\":\"setAudioMute\",\"id\":41,\"params\":[{\"status\":true}],\"version\":\"1.0\"}"
    postAPICall(lib,json)
    pauseExecution(2000)
    getMuteStatus()
}

def setUnMute(){
    if (logEnable) log.debug "Executing 'setUnMute' "
    def lib = "/sony/audio"
    def json = "{\"method\":\"setAudioMute\",\"id\":42,\"params\":[{\"status\":false}],\"version\":\"1.0\"}"
    postAPICall(lib,json)
    pauseExecution(2000)
    getMuteStatus()
}

def getSystemInfo(){
    if (logEnable) log.debug "Executing 'getSystemInfo' "
    def lib = "/sony/system"
    def json = "{\"method\":\"getSystemInformation\",\"id\":99,\"params\":[],\"version\":\"1.0\"}"
    postAPICall(lib,json)
}

def getInterfaceInfo(){
    if (logEnable) log.debug "Executing 'getInterfaceInfo' "
    def lib = "/sony/system"
    def json = "{\"method\":\"getInterfaceInformation\",\"id\":98,\"params\":[],\"version\":\"1.0\"}"
    postAPICall(lib,json)
}

def getEthernetSettings(){
    if (logEnable) log.debug "Executing 'getEthernetSettings' "
    def lib = "/sony/system"
    def json = "{\"method\":\"getNetworkSettings\",\"id\":97,\"params\":[{\"netif\":\"eth0\"}],\"version\":\"1.0\"}"
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
    def lib = libpath
    def json = jsonmsg
    postAPICall(lib,json)
}

def getCurrentSource(){
        if (logEnable) log.debug "Executing 'getCurrentSource' "
    def lib = "/sony/avContent"
    def json = "{\"method\":\"getPlayingContentInfo\",\"id\":70,\"params\":[{\"output\":\"\"}],\"version\":\"1.0\"}"
    postAPICall(lib,json)
}

def Reboot(){
    if (logEnable) log.debug "Reboot Pressed"
    if (logEnable) log.debug "Executing 'Reboot' "
    def lib = "/sony/system"
    def json = "{\"method\":\"requestReboot\",\"id\":10,\"params\": [],\"version\":\"1.0\"}"
    postAPICall(lib,json)
}

def TerminateApps(){
    if (logEnable) log.debug "Terminate Apps Pressed"
    if (logEnable) log.debug "Executing 'TerminateApps' "
    def lib = "/sony/appControl"
    def json = "{\"method\":\"terminateApps\",\"id\":55,\"params\":[],\"version\":\"1.0\"}"
    postAPICall(lib,json)
    pauseExecution(2000)
    refresh()
}

def InputSelect(def inputname){
    if (logEnable) log.debug "InputSelect Pressed with ${inputname}"
def input = null
    if (inputname == "HDMI1") { input = "extInput:hdmi?port=1"}
    if (inputname == "HDMI2") { input = "extInput:hdmi?port=2"}
    if (inputname == "HDMI3") { input = "extInput:hdmi?port=3"}
    if (inputname == "HDMI4") { input = "extInput:hdmi?port=4"}
    SetInput(input)

}

def SetInput(input){
    if (logEnable) log.debug "Executing 'SetInput' "
    def lib = "/sony/avContent"
    def json = "{\"method\":\"setPlayContent\",\"id\":101,\"params\":[{\"uri\":\"${input}\"}],\"version\":\"1.0\"}"
    postAPICall(lib,json)
}

def LaunchApp(def appname){
    if (logEnable) log.debug "LaunchApp Pressed with ${appname}"
def app = null
    if (appname == "YouTube") { app = "com.sony.dtv.com.google.android.youtube.tv.com.google.android.apps.youtube.tv.activity.ShellActivity"}
    if (appname == "TV") { app = "com.sony.dtv.com.sony.dtv.tvx.com.sony.dtv.tvx.MainActivity"}
    if (appname == "Program Guide") { app = "com.sony.dtv.com.sony.dtv.tvxlauncher.programguide.com.sony.dtv.tvxlauncher.programguide.MainActivity"}
    if (appname == "Hulu") { app = "com.sony.dtv.com.hulu.livingroomplus.com.hulu.livingroomplus.WKFactivity"}
    if (appname == "Pluto TV") { app = "com.sony.dtv.tv.pluto.android.tv.pluto.android.EntryPoint"}
    if (appname == "Prime Video") { app = "com.sony.dtv.com.amazon.amazonvideo.livingroom.com.amazon.ignition.IgnitionActivity"}
    if (appname == "Netflix") { app = "com.sony.dtv.com.netflix.ninja.com.netflix.ninja.MainActivity"}
    if (appname == "tinyCam PRO") { app = "ecom.sony.dtv.com.alexvas.dvr.pro.com.alexvas.dvr.activity.TvMainActivity"}
    if (appname == "Disney+") { app = "com.sony.dtv.com.disney.disneyplus.com.bamtechmedia.dominguez.main.MainActivity"}
    if (appname == "ESPN") { app = "com.sony.dtv.com.espn.score_center.com.espn.androidtv.ui.LoadingActivity"}
    if (appname == "Plex") { app = "com.sony.dtv.com.plexapp.android.com.plexapp.plex.activities.SplashActivity"}
    if (appname == "Sling TV") { app = "com.sony.dtv.com.sling.com.movenetworks.StartupActivity"}
    if (appname == "Spotify") { app = "com.sony.dtv.com.spotify.tv.android.com.spotify.tv.android.SpotifyTVActivity"}

    setActiveApp(app)
}

def setActiveApp(app){
    if (logEnable) log.debug "Executing 'setActiveApp' with ${app} "
    def lib = "/sony/appControl"
    def json = "{\"method\":\"setActiveApp\",\"id\":601,\"params\":[{\"uri\":\"${app}\"}],\"version\":\"1.0\"}"
    postAPICall(lib,json)

}

def SendURL(def url){
    def urlpath = url
    if (logEnable) log.debug "Executing 'setURL' "
    def lib = "/sony/appControl"
    def json = "{\"method\":\"setActiveApp\",\"id\":601,\"params\":[{\"uri\":\"localapp://webappruntime?url=${urlpath}\"}],\"version\":\"1.0\"}"
    postAPICall(lib,json)
}

def getSoundSettings(){
    if (logEnable) log.debug "Executing 'getSoundSettings' "
    def lib = "/sony/audio"
    def json = "{\"method\":\"getSoundSettings\",\"id\":73,\"params\":[{\"target\":\"\"}],\"version\":\"1.1\"}"
    postAPICall(lib,json)
}

def getPowerSaveMode(){
    if (logEnable) log.debug "Executing 'getPowerSaveMode' "
    def lib = "/sony/system"
    def json = "{\"method\":\"getPowerSavingMode\",\"id\":89,\"params\":[],\"version\":\"1.0\"}"
    postAPICall(lib,json)
}

def getWOLMode(){
    if (logEnable) log.debug "Executing 'getWOLMode' "
     def lib = "/sony/system"
    def json = "{\"method\":\"getWolMode\",\"id\":86,\"params\":[],\"version\":\"1.0\"}"
    postAPICall(lib,json)
}



//This will convert the selected key to the IRCC Mode
def keyPress(key) {
	//if (!isValidKey(key)) {
		//log.warning("Invalid key press: ${key}")
		//return
	//}
    if (logEnable) log.debug "Executing '${key}'"
convertkey(key)
}


private convertkey(key){
    def remotecommand = null
    if (key == "Num1") { remotecommand = "AAAAAQAAAAEAAAAAAw=="}
    if (key == "Num2") { remotecommand = "AAAAAQAAAAEAAAABAw=="}
    if (key == "Num3") { remotecommand = "AAAAAQAAAAEAAAACAw=="}
    if (key == "Num4") { remotecommand = "AAAAAQAAAAEAAAADAw=="}
    if (key == "Num5") { remotecommand = "AAAAAQAAAAEAAAAEAw=="}
    if (key == "Num6") { remotecommand = "AAAAAQAAAAEAAAAFAw=="}
    if (key == "Num7") { remotecommand = "AAAAAQAAAAEAAAAGAw=="}
    if (key == "Num8") { remotecommand = "AAAAAQAAAAEAAAAHAw=="}
    if (key == "Num9") { remotecommand = "AAAAAQAAAAEAAAAIAw=="}
    if (key == "Num0") { remotecommand = "AAAAAQAAAAEAAAAJAw=="}
    if (key == "Num11") { remotecommand = "AAAAAQAAAAEAAAAKAw=="}
    if (key == "Num12") { remotecommand = "AAAAAQAAAAEAAAALAw=="}
    if (key == "Enter") { remotecommand = "AAAAAQAAAAEAAAALAw=="}
    if (key == "GGuide") { remotecommand = "AAAAAQAAAAEAAAAOAw=="}
    if (key == "ChannelUp") { remotecommand = "AAAAAQAAAAEAAAAQAw=="}
    if (key == "ChannelDown") { remotecommand = "AAAAAQAAAAEAAAARAw=="}
    if (key == "VolumeUp") { remotecommand = "AAAAAQAAAAEAAAASAw=="}
    if (key == "VolumeDown") { remotecommand = "AAAAAQAAAAEAAAATAw=="}
    if (key == "Mute") { remotecommand = "AAAAAQAAAAEAAAAUAw=="}
    if (key == "TvPower") { remotecommand = "AAAAAQAAAAEAAAAVAw=="}
    if (key == "Audio") { remotecommand = "AAAAAQAAAAEAAAAXAw=="}
    if (key == "MediaAudioTrack") { remotecommand = "AAAAAQAAAAEAAAAXAw=="}
    if (key == "Tv") { remotecommand = "AAAAAQAAAAEAAAAkAw=="}
    if (key == "Input") { remotecommand = "AAAAAQAAAAEAAAAlAw=="}
    if (key == "TvInput") { remotecommand = "AAAAAQAAAAEAAAAlAw=="}
    if (key == "TvAntennaCable") { remotecommand = "AAAAAQAAAAEAAAAqAw=="}
    if (key == "WakeUp") { remotecommand = "AAAAAQAAAAEAAAAuAw=="}
    if (key == "PowerOff") { remotecommand = "AAAAAQAAAAEAAAAvAw=="}
    if (key == "Sleep") { remotecommand = "AAAAAQAAAAEAAAAvAw=="}
    if (key == "Right") { remotecommand = "AAAAAQAAAAEAAAAzAw=="}
    if (key == "Left") { remotecommand = "AAAAAQAAAAEAAAA0Aw=="}
    if (key == "SleepTimer") { remotecommand = "AAAAAQAAAAEAAAA2Aw=="}
    if (key == "Analog2") { remotecommand = "AAAAAQAAAAEAAAA4Aw=="}
    if (key == "TvAnalog") { remotecommand = "AAAAAQAAAAEAAAA4Aw=="}
    if (key == "Display") { remotecommand = "AAAAAQAAAAEAAAA6Aw=="}
    if (key == "Jump") { remotecommand = "AAAAAQAAAAEAAAA7Aw=="}
    if (key == "PicOff") { remotecommand = "AAAAAQAAAAEAAAA+Aw=="}
    if (key == "PictureOff") { remotecommand = "AAAAAQAAAAEAAAA+Aw=="}
    if (key == "Teletext") { remotecommand = "AAAAAQAAAAEAAAA/Aw=="}
    if (key == "Video1") { remotecommand = "AAAAAQAAAAEAAABAAw=="}
    if (key == "Video2") { remotecommand = "AAAAAQAAAAEAAABBAw=="}
    if (key == "AnalogRgb1") { remotecommand = "AAAAAQAAAAEAAABDAw=="}
    if (key == "Home") { remotecommand = "AAAAAQAAAAEAAABgAw=="}
    if (key == "Exit") { remotecommand = "AAAAAQAAAAEAAABjAw=="}
    if (key == "PictureMode") { remotecommand = "AAAAAQAAAAEAAABkAw=="}
    if (key == "Confirm") { remotecommand = "AAAAAQAAAAEAAABlAw=="}
    if (key == "Up") { remotecommand = "AAAAAQAAAAEAAAB0Aw=="}
    if (key == "Down") { remotecommand = "AAAAAQAAAAEAAAB1Aw=="}
    if (key == "ClosedCaption") { remotecommand = "AAAAAgAAAKQAAAAQAw=="}
    if (key == "Component1") { remotecommand = "AAAAAgAAAKQAAAA2Aw=="}
    if (key == "Component2") { remotecommand = "AAAAAgAAAKQAAAA3Aw=="}
    if (key == "Wide") { remotecommand = "AAAAAgAAAKQAAAA9Aw=="}
    if (key == "EPG") { remotecommand = "AAAAAgAAAKQAAABbAw=="}
    if (key == "PAP") { remotecommand = "AAAAAgAAAKQAAAB3Aw=="}
    if (key == "TenKey") { remotecommand = "AAAAAgAAAJcAAAAMAw=="}
    if (key == "BSCS") { remotecommand = "AAAAAgAAAJcAAAAQAw=="}
    if (key == "Ddata") { remotecommand = "AAAAAgAAAJcAAAAVAw=="}
    if (key == "Stop") { remotecommand = "AAAAAgAAAJcAAAAYAw=="}
    if (key == "Pause") { remotecommand = "AAAAAgAAAJcAAAAZAw=="}
    if (key == "Play") { remotecommand = "AAAAAgAAAJcAAAAaAw=="}
    if (key == "Rewind") { remotecommand = "AAAAAgAAAJcAAAAbAw=="}
    if (key == "Forward") { remotecommand = "AAAAAgAAAJcAAAAcAw=="}
    if (key == "DOT") { remotecommand = "AAAAAgAAAJcAAAAdAw=="}
    if (key == "Rec") { remotecommand = "AAAAAgAAAJcAAAAgAw=="}
    if (key == "Return") { remotecommand = "AAAAAgAAAJcAAAAjAw=="}
    if (key == "Blue") { remotecommand = "AAAAAgAAAJcAAAAkAw=="}
    if (key == "Red") { remotecommand = "AAAAAgAAAJcAAAAlAw=="}
    if (key == "Green") { remotecommand = "AAAAAgAAAJcAAAAmAw=="}
    if (key == "Yellow") { remotecommand = "AAAAAgAAAJcAAAAnAw=="}
    if (key == "SubTitle") { remotecommand = "AAAAAgAAAJcAAAAoAw=="}
    if (key == "CS") { remotecommand = "AAAAAgAAAJcAAAArAw=="}
    if (key == "BS") { remotecommand = "AAAAAgAAAJcAAAAsAw=="}
    if (key == "Digital") { remotecommand = "AAAAAgAAAJcAAAAyAw=="}
    if (key == "Options") { remotecommand = "AAAAAgAAAJcAAAA2Aw=="}
    if (key == "Media") { remotecommand = "AAAAAgAAAJcAAAA4Aw=="}
    if (key == "Prev") { remotecommand = "AAAAAgAAAJcAAAA8Aw=="}
    if (key == "Next") { remotecommand = "AAAAAgAAAJcAAAA9Aw=="}
    if (key == "DpadCenter") { remotecommand = "AAAAAgAAAJcAAABKAw=="}
    if (key == "CursorUp") { remotecommand = "AAAAAgAAAJcAAABPAw=="}
    if (key == "CursorDown") { remotecommand = "AAAAAgAAAJcAAABQAw=="}
    if (key == "CursorLeft") { remotecommand = "AAAAAgAAAJcAAABNAw=="}
    if (key == "CursorRight") { remotecommand = "AAAAAgAAAJcAAABOAw=="}
    if (key == "ShopRemoteControlForcedDynamic") { remotecommand = "AAAAAgAAAJcAAABqAw=="}
    if (key == "FlashPlus") { remotecommand = "AAAAAgAAAJcAAAB4Aw=="}
    if (key == "FlashMinus") { remotecommand = "AAAAAgAAAJcAAAB5Aw=="}
    if (key == "DemoMode") { remotecommand = "AAAAAgAAAJcAAAB8Aw=="}
    if (key == "Analog") { remotecommand = "AAAAAgAAAHcAAAANAw=="}
    if (key == "Mode3D") { remotecommand = "AAAAAgAAAHcAAABNAw=="}
    if (key == "DigitalToggle") { remotecommand = "AAAAAgAAAHcAAABSAw=="}
    if (key == "DemoSurround") { remotecommand = "AAAAAgAAAHcAAAB7Aw=="}
    if (key == "*AD") { remotecommand = "AAAAAgAAABoAAAA7Aw=="}
    if (key == "AudioMixUp") { remotecommand = "AAAAAgAAABoAAAA8Aw=="}
    if (key == "AudioMixDown") { remotecommand = "AAAAAgAAABoAAAA9Aw=="}
    if (key == "PhotoFrame") { remotecommand = "AAAAAgAAABoAAABVAw=="}
    if (key == "Tv_Radio") { remotecommand = "AAAAAgAAABoAAABXAw=="}
    if (key == "SyncMenu") { remotecommand = "AAAAAgAAABoAAABYAw=="}
    if (key == "Hdmi1") { remotecommand = "AAAAAgAAABoAAABaAw=="}
    if (key == "Hdmi2") { remotecommand = "AAAAAgAAABoAAABbAw=="}
    if (key == "Hdmi3") { remotecommand = "AAAAAgAAABoAAABcAw=="}
    if (key == "Hdmi4") { remotecommand = "AAAAAgAAABoAAABdAw=="}
    if (key == "TopMenu") { remotecommand = "AAAAAgAAABoAAABgAw=="}
    if (key == "PopUpMenu") { remotecommand = "AAAAAgAAABoAAABhAw=="}
    if (key == "OneTouchTimeRec") { remotecommand = "AAAAAgAAABoAAABkAw=="}
    if (key == "OneTouchView") { remotecommand = "AAAAAgAAABoAAABlAw=="}
    if (key == "DUX") { remotecommand = "AAAAAgAAABoAAABzAw=="}
    if (key == "FootballMode") { remotecommand = "AAAAAgAAABoAAAB2Aw=="}
    if (key == "iManual") { remotecommand = "AAAAAgAAABoAAAB7Aw=="}
    if (key == "Netflix") { remotecommand = "AAAAAgAAABoAAAB8Aw=="}
    if (key == "Assists") { remotecommand = "AAAAAgAAAMQAAAA7Aw=="}
    if (key == "FeaturedApp") { remotecommand = "AAAAAgAAAMQAAABEAw=="}
    if (key == "FeaturedAppVOD") { remotecommand = "AAAAAgAAAMQAAABFAw=="}
    if (key == "GooglePlay") { remotecommand = "AAAAAgAAAMQAAABGAw=="}
    if (key == "ActionMenu") { remotecommand = "AAAAAgAAAMQAAABLAw=="}
    if (key == "Help") { remotecommand = "AAAAAgAAAMQAAABNAw=="}
    if (key == "TvSatellite") { remotecommand = "AAAAAgAAAMQAAABOAw=="}
    if (key == "WirelessSubwoofer") { remotecommand = "AAAAAgAAAMQAAAB+Aw=="}
    if (key == "AndroidMenu") { remotecommand = "AAAAAgAAAMQAAABPAw=="}
    if (key == "RecorderMenu") { remotecommand = "AAAAAgAAAMQAAABIAw=="}
    if (key == "STBMenu") { remotecommand = "AAAAAgAAAMQAAABJAw=="}
    if (key == "MuteOn") { remotecommand = "AAAAAgAAAMQAAAAsAw=="}
    if (key == "MuteOff") { remotecommand = "AAAAAgAAAMQAAAAtAw=="}
    if (key == "AudioOutput_AudioSystem") { remotecommand = "AAAAAgAAAMQAAAAiAw=="}
    if (key == "AudioOutput_TVSpeaker") { remotecommand = "AAAAAgAAAMQAAAAjAw=="}
    if (key == "AudioOutput_Toggle") { remotecommand = "AAAAAgAAAMQAAAAkAw=="}
    if (key == "ApplicationLauncher") { remotecommand = "AAAAAgAAAMQAAAAqAw=="}
    if (key == "YouTube") { remotecommand = "AAAAAgAAAMQAAABHAw=="}
    if (key == "PartnerApp1") { remotecommand = "AAAAAgAACB8AAAAAAw=="}
    if (key == "PartnerApp2") { remotecommand = "AAAAAgAACB8AAAABAw=="}
    if (key == "PartnerApp3") { remotecommand = "AAAAAgAACB8AAAACAw=="}
    if (key == "PartnerApp4") { remotecommand = "AAAAAgAACB8AAAADAw=="}
    if (key == "PartnerApp5") { remotecommand = "AAAAAgAACB8AAAAEAw=="}
    if (key == "PartnerApp6") { remotecommand = "AAAAAgAACB8AAAAFAw=="}
    if (key == "PartnerApp7") { remotecommand = "AAAAAgAACB8AAAAGAw=="}
    if (key == "PartnerApp8") { remotecommand = "AAAAAgAACB8AAAAHAw=="}
    if (key == "PartnerApp9") { remotecommand = "AAAAAgAACB8AAAAIAw=="}
    if (key == "PartnerApp10") { remotecommand = "AAAAAgAACB8AAAAJAw=="}
    if (key == "PartnerApp11") { remotecommand = "AAAAAgAACB8AAAAKAw=="}
    if (key == "PartnerApp12") { remotecommand = "AAAAAgAACB8AAAALAw=="}
    if (key == "PartnerApp13") { remotecommand = "AAAAAgAACB8AAAAMAw=="}
    if (key == "PartnerApp14") { remotecommand = "AAAAAgAACB8AAAANAw=="}
    if (key == "PartnerApp15") { remotecommand = "AAAAAgAACB8AAAAOAw=="}
    if (key == "PartnerApp16") { remotecommand = "AAAAAgAACB8AAAAPAw=="}
    if (key == "PartnerApp17") { remotecommand = "AAAAAgAACB8AAAAQAw=="}
    if (key == "PartnerApp18") { remotecommand = "AAAAAgAACB8AAAARAw=="}
    if (key == "PartnerApp19") { remotecommand = "AAAAAgAACB8AAAASAw=="}
    if (key == "PartnerApp20") { remotecommand = "AAAAAgAACB8AAAATAw=="}


RemoteIRCC(key,remotecommand)
}



//Below Function will send button commands from the remote. Play, Pause, Skip, Etc
private RemoteIRCC(key,remotecommand){
	if (logEnable) log.debug "Sending Button: ${key} ${remotecommand}"
    def rawcmd = "${remotecommand}"
    def sonycmd = new hubitat.device.HubSoapAction(
            path:    '/sony/IRCC',
            urn:     "urn:schemas-sony-com:service:IRCC:1",
            action:  "X_SendIRCC",
            body:    ["IRCCCode":rawcmd],
            headers: [Host:"${settings.ipAddress}:${settings.ipPort}", 'X-Auth-PSK':"${settings.PSK}"]
     )
     sendHubCommand(sonycmd)
     if (logEnable) log.debug( "hubAction = ${sonycmd}" )
}

def parse(description) {
    //adding to prevent error during button command
  if (logEnable) log.debug ("Parsing '${description}'")
  def msg = parseLanMessage(description)
	if (logEnable) log.debug "${msg}"
}
