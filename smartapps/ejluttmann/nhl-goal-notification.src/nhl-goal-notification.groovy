/**
 *  NHL Goal Notification
 *
 *  Copyright 2017 Eric Luttmann
 *
 *  Based in part on "NHL Goal Light" by Patrick Mjoen
 *  
 *  Description:
 *  This app was built to provide a solution similar to Budweiser Red Light.
 *
 *  When your hockey team scores, the app can be setup to control lights, switches
 *  and play your teams goal scoring song.
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

include 'asynchttp_v1'
import groovy.json.JsonSlurper
 
definition(
    name: "NHL Goal Notification",
    namespace: "ejluttmann",
    author: "Eric Luttmann",
    description: "Get notified when your hockey team scores by controlling lights, switches and playing your teams goal scoring song.",
    category: "My Apps",
    iconUrl: "https://cloud.githubusercontent.com/assets/2913371/22167524/3390bf68-df24-11e6-94c6-b099063842df.png",
    iconX2Url: "https://cloud.githubusercontent.com/assets/2913371/22167524/3390bf68-df24-11e6-94c6-b099063842df.png",
    iconX3Url: "https://cloud.githubusercontent.com/assets/2913371/22167524/3390bf68-df24-11e6-94c6-b099063842df.png")


preferences {
	section("NHL Team") {
		input "team", "enum", title: "Team Selection", required: true, displayDuringSetup: true, options: ["Avalanche","Blackhawks","Blue Jackets","Blues","Bruins","Canadiens","Canucks","Capitals","Coyotes","Devils","Ducks","Flames","Flyers","Hurricanes","Islanders","Jets","Kings","Lighting","Maple Leafs","Oilers","Panthers","Penguins","Predators","Rangers","Red Wings","Sabres","Senators","Sharks","Stars","Wild"]
	}
    
    section("Switches To Turn On"){
         input "switches", "capability.switch", title: "Devices Selection", required: false, multiple: true, displayDuringSetup: true
         input "switchOnFor", "enum", title: "Turn Off After (seconds)", required: false, defaultValue: "5", multiple: false, displayDuringSetup: true, options: ["1","2","3","4","5","6","7","8","9","10"]
    }
    
	section("Flash Switches"){
		input "flashes", "capability.switch", title: "These lights", multiple: true, required: false
		input "numFlashes", "number", title: "This number of times", required: false, defaultValue: "3"
		input "flashOnFor", "number", title: "On for", required: false, defaultValue: "1000"
		input "flashOffFor", "number", title: "Off for", required: false, defaultValue: "1000"
	}
    
    section ("Speakers"){
         input "sound", "capability.musicPlayer", title: "Music Devices Selection", required: false, displayDuringSetup: true
         input "soundDuration", "enum", title: "Sound duration(seconds)", required: false, defaultValue: "5", multiple: false, displayDuringSetup: true, options: ["1","2","3","4","5","6","7","8","9","10"]
    }
    
	section("Sirens"){
		input "sirens", "capability.alarm", title: "Which?", required: false, multiple: true
	}
    
    section( "Notifications" ) {
        input "sendPushMessage", "enum", title: "Send a push notification?", metadata:[values:["Yes","No"]], required:false
        input "textPhone", "phone", title: "Send a Text Message?", required: false
    }
    
    section("Debug Logging") {
         input "debug_pref", "bool", title: "Debug Logging", defaultValue: "false", displayDuringSetup: true
    }
}

def installed() {
	log.debug "Installed with settings: ${settings}"

	initialize()
}

def updated() {
	log.debug "Updated with settings: ${settings}"

	unsubscribe()
	initialize()
}

def initialize() {
	state.NHL_API_URL = "http://statsapi.web.nhl.com/api/v1"
    
    getTeams()
}

def getTeamsHandler(resp, data) {
    // get the status code of the response
    log.debug "response status code: ${resp.status}"
    log.debug "data passed to response handler: $data"
    
    if (resp.status == 200){
    	if (settings.debug_pref == true) log.debug "raw response: ${resp.getData()}"
        
    }
    else {
		log.debug "Request Failed To Return Data"
	}
}

def getTeams() {
    def todaysDate = new Date().format('yyyyMMdd')
    def params = [uri: "${state.NHL_API_URL}/schedule"] 
    log.debug "Requesting ${settings.team} scores for ${todaysDate}"
    asynchttp_v1.get(getTeamsHandler, params)
}