/**
 *  Virtual Air Quality Monitor
 *
 *  Collects cloud-based data from Airthings Wave Plus for T, H, P, CO2, TVOC and
 *  Radon values.
 *
 *  Requires username and password for Airthings web/mobile login, as well as the
 *  device ID. This can be retrieved from the URL when accessing the 'wave' device
 *  from a web browser.
 *
 *  This driver retrieves real-time statistics from the Airthings Cloud service.
 *
 *  Driver provides native support for 'back-off' functionality when downloading CSV
 *  file, since large files can take some time to generate and become available.
 *
 *  Original driver "Copyright 2020 LostJen". Updated the driver to support Airthings Wave
 *  cloud-based reporting.
 *
 *  Updated driver w/scheduling based on Joseph Orlando's (joeyx22lm) Airthings driver
 *
 *  Last Update 02/14/2022
 *
 *  v0.0.2 - added scheduling
 *
 * ------------------------------------------------------------------------------------------------------------------------------
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 * ------------------------------------------------------------------------------------------------------------------------------
 *
 */
metadata {
  definition(name: "Airthings Hub Realtime Air Quality Monitor", namespace: "com.joeyorlando.hubitat.airthings", author: "joeyx22lm", importUrl: "https://raw.githubusercontent.com/joeyx22lm/hubitat-extensions/main/airthings-wave-monitor/airthings-hub-historical.groovy") {
    capability "Actuator"
    capability "Sensor"

    capability "Temperature Measurement"
    capability "Relative Humidity Measurement"
    capability "Pressure Measurement"
    capability "Carbon Dioxide Measurement"
    capability "Initialize"
    capability "Polling"
    capability "Battery"

    attribute "battery", "number"
    attribute "temperature", "number"
    attribute "humidity", "number"
    attribute "pressure", "number"
    attribute "carbonDioxide", "number"
    attribute "tVOC", "number"
    attribute "radonShortTermAvg", "number"
    attribute "lastUpdate", "date"

    preferences {
      section("Airthings Cloud Settings:") {
        input name: "username", type: "string", title: "Airthings Cloud Username", required: true, displayDuringSetup: true, defaultValue: null
        input name: "password", type: "password", title: "Airthings Cloud Password", required: true, displayDuringSetup: true, defaultValue: null
        input name: "deviceId", type: "string", title: "Airthings Device ID", required: true, displayDuringSetup: true, defaultValue: null
        input "poll_interval", "enum", title: "Poll Interval:", required: false, defaultValue: "5 Minutes", options: ["5 Minutes", "10 Minutes", "15 Minutes", "30 Minutes", "1 Hour", "3 Hours"]
        input "debug", "bool", required: true, defaultValue: false, title: "Debug Logging"
      }
    }
  }
}

def poll() {
  if (debug) log.debug("AirThings: poll() called")
  fetchData()
}

def fetchData() {
  if (debug) log.debug("AirThings: attempting to fetch data via CSV download -- last pull date: ${device.currentValue("
    lastUpdate ")}")
  doLogin()
}

// Step #1: Login with username and password, receive bearer token
def doLogin() {
  try {
    def postParams = [
      uri: "https://accounts-api.airthings.com/v1/token",
      requestContentType: "application/json",
      contentType: 'application/json',
      body: [
        username: username,
        password: password,
        "grant_type": "password",
        "client_id": "accounts"
      ],
      timeout: 60
    ]
    asynchttpPost('handleLogin', postParams)
  } catch (Exception e) {
    if (e.message.toString() != "OK") {
      log.error("AirThings: unable to login as username: ${username} - ${e.message}")
    }
  }
}

def handleLogin(response, data) {
  if (response.hasError()) {
    log.error("AirThings: response.error = ${response.getErrorMessage()}")
    return;
  }
  if (response.getStatus() != 200) {
    log.error("AirThings: unexpected HTTP Status Code: ${response.getStatus()}")
    return;
  }
  def body = response.getJson()
  if (!body.containsKey("access_token")) {
    log.error("AirThings: HTTP response did not contain 'access_token': ${body}")
    return;
  }
  if (debug) log.debug("AirThings: logged in successfully as username: ${username}")
  doOAuthAuthorization(body.get("access_token").trim())
}

// Step #2: Exchange bearer token for OAuth authorization code
def doOAuthAuthorization(bearerToken) {
  try {
    def postParams = [
      uri: "https://accounts-api.airthings.com/v1/authorize?client_id=dashboard&redirect_uri=https%3A%2F%2Fdashboard.airthings.com",
      requestContentType: "application/json",
      contentType: 'application/json',
      headers: [
        authorization: "Bearer " + bearerToken,
      ],
      body: [
        "scope": [
          "dashboard"
        ]
      ],
      timeout: 60
    ]
    asynchttpPost('handleOAuthAuthorization', postParams)
  } catch (Exception e) {
    if (e.message.toString() != "OK") {
      log.error("AirThings: unable to POST to OAuthAuthorization with token ${bearerToken} - ${e.message}")
    }
  }
}

def handleOAuthAuthorization(response, data) {
  if (response.hasError()) {
    log.error("AirThings: OAuth authorization response.error = ${response.getErrorMessage()}")
    return;
  }
  if (response.getStatus() != 200) {
    log.error("AirThings: OAuth authorization Unexpected HTTP Status Code: ${response.getStatus()}")
    return;
  }
  def body = response.getJson()
  if (!body.containsKey("redirect_uri")) {
    log.error("AirThings: OAuth authorization HTTP response did not contain 'redirect_uri': ${body}")
    return;
  }
  try {
    def authCode = body.get("redirect_uri").split("code=")[1];
    if (debug) log.debug("AirThings: successfully obtained OAuth authorization code")
    doOAuthAuthentication(authCode)
  } catch (Exception e) {
    log.error("AirThings: unable to extract OAuth authorization code from body: ${body} - ${e.message}")
  }
}

// Step #3: Exchange OAuth authorization code for OAuth access token
def doOAuthAuthentication(authCode) {
  try {
    def postParams = [
      uri: "https://accounts-api.airthings.com/v1/token",
      requestContentType: "application/json",
      contentType: 'application/json',
      body: [
        "grant_type": "authorization_code",
        "client_id": "dashboard",
        "client_secret": "e333140d-4a85-4e3e-8cf2-bd0a6c710aaa",
        "code": authCode,
        "redirect_uri": "https://dashboard.airthings.com"
      ],
      timeout: 60
    ]
    asynchttpPost('handleOAuthAuthentication', postParams)
  } catch (Exception e) {
    if (e.message.toString() != "OK") {
      log.error("AirThings: unable to POST to OAuth authentication with authCode ${authCode} - ${e.message}")
    }
  }
}

def handleOAuthAuthentication(response, data) {
  if (response.hasError()) {
    log.error("AirThings: OAuth Authentication response.error = ${response.getErrorMessage()}")
    return;
  }
  if (response.getStatus() != 200) {
    log.error("AirThings: OAuth Authentication Unexpected HTTP Status Code: ${response.getStatus()}")
    return;
  }
  def body = response.getJson()
  if (!body.containsKey("access_token")) {
    log.error("AirThings: OAuth Authentication HTTP response did not contain 'access_token': ${body}")
    return;
  }
  try {
    def accessToken = body.get("access_token").trim();
    if (debug) log.debug("AirThings: successfully obtained OAuth authentication token")
    fetchStatistics(accessToken)
  } catch (Exception e) {
    log.error("AirThings: unable to extract OAuth Authentication token from body: ${body}")
  }
}

// Step #4: Fetch latest statistics using OAuth access token
def fetchStatistics(access_token) {
  try {
    use(groovy.time.TimeCategory) {
      def dateNow = new Date();
      def startRange = (dateNow - 1. hours).format("yyyy-MM-dd'T'HH:mm:ss")
      def endRange = (dateNow + 1. hours).format("yyyy-MM-dd'T'HH:mm:ss")
      if (debug) log.debug("AirThings: fetching statistics between ${startRange} and ${endRange}")
      asynchttpGet('handleStatistics', [
        uri: "https://web-api.airthin.gs/v1/devices/${deviceId}/segments/latest/samples?from=${startRange}&to=${endRange}",
        headers: [
          authorization: access_token,
          accept: 'application/json'
        ],
        contentType: 'application/json',
        timeout: 60
      ])
    }
  } catch (Exception e) {
    if (e.message.toString() != "OK") {
      log.error("AirThings: unable to retrieve CSV download URL for deviceId: ${deviceId} - ${e.message}")
    }
  }
}

def handleStatistics(response, data) {
  if (response.hasError()) {
    log.error("AirThings: response.error = ${response.getErrorMessage()}")
    return;
  }
  if (response.getStatus() != 200) {
    log.error("AirThings: unexpected HTTP Status Code: ${response.getStatus()}")
    return;
  }

  def body = response.getJson()
  if (!body.containsKey("sensors")) {
    log.error("AirThings: HTTP response did not contain expected response: ${body}")
    return;
  }

  String batteryPercentageStr = body.get("batteryPercentage");
  if (batteryPercentageStr != null) {
    Double batteryPercentage = Double.valueOf(batteryPercentageStr).round(2);
    sendEvent(name: "battery", value: batteryPercentage, unit: "%", isStateChange: !batteryPercentage.equals(device.currentValue("battery")))
  }

  boolean dataFetched = false;
  for (def sensor: body.get("sensors")) {
    if (debug) log.debug("AirThings: parsing statistics for sensor ${sensor.type}")
    if ("radonShortTermAvg".equalsIgnoreCase(sensor.type) && sensor.measurements.size() > 0) {
      Double value = sensor.measurements[sensor.measurements.size() - 1];
      sendEvent(name: "radonShortTermAvg", value: value, unit: "pCi/L", isStateChange: !value.equals(device.currentValue("radonShortTermAvg")))
      dataFetched = true
    } else if ("temp".equalsIgnoreCase(sensor.type) && sensor.measurements.size() > 0) {
      Double value = sensor.measurements[sensor.measurements.size() - 1];
      sendEvent(name: "temperature", value: value, unit: "°", isStateChange: !value.equals(device.currentValue("temperature")))
      dataFetched = true
    } else if ("humidity".equalsIgnoreCase(sensor.type) && sensor.measurements.size() > 0) {
      Integer value = sensor.measurements[sensor.measurements.size() - 1];
      sendEvent(name: "humidity", value: value, unit: "%", isStateChange: !value.equals(device.currentValue("humidity")))
      dataFetched = true
    } else if ("pressure".equalsIgnoreCase(sensor.type) && sensor.measurements.size() > 0) {
      Double value = sensor.measurements[sensor.measurements.size() - 1];
      sendEvent(name: "pressure", value: value == null ? null : value.round(1), unit: "mbar", isStateChange: !value.equals(device.currentValue("pressure")))
      dataFetched = true
    } else if ("co2".equalsIgnoreCase(sensor.type) && sensor.measurements.size() > 0) {
      Integer value = sensor.measurements[sensor.measurements.size() - 1];
      sendEvent(name: "carbonDioxide", value: value, unit: "ppm", isStateChange: !value.equals(device.currentValue("carbonDioxide")))
      dataFetched = true
    } else if ("voc".equalsIgnoreCase(sensor.type) && sensor.measurements.size() > 0) {
      Integer value = sensor.measurements[sensor.measurements.size() - 1];
      sendEvent(name: "tVOC", value: value, unit: "ppb", isStateChange: !value.equals(device.currentValue("tVOC")))
      dataFetched = true
    }
  }

  if (dataFetched) {
    sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd'T'HH:mm:ss"), isStateChange: true)
    if (debug) log.debug("AirThings: completed processing statistics for deviceId: ${deviceId}")
  } else {
    log.error("AirThings: unable to process statistics for deviceId: ${deviceId}")
  }
}

def poll_schedule() {
  poll()
}

def initialize() {
  unschedule()
  if (debug) log.debug("AirThings: initialize() called")

  if (!acurite_username || !acurite_password || !device_id) {
    log.error("AirThings: required fields not completed.  Please complete for proper operation.")
    return
  }
  def poll_interval_cmd = (settings?.poll_interval ?: "5 Minutes").replace(" ", "")
  "runEvery${poll_interval_cmd}"(poll_schedule)
  if (debug) log.debug("AirThings: scheduling as runEvery" + poll_interval_cmd)
}