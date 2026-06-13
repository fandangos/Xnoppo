#
# Thanks for websocket-client library.
#
# Copyright 2018 Hiroki Ohtani.
#
# Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:
#
# 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
#
# 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
#
# 3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.
#
# THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.


import threading
from websocket import WebSocketApp
import logging
import json
import time
from .Emby_http import EmbyHttp
from .Xnoppo import *


class xnoppo_ws(threading.Thread):
    emby_state = ''
    stop_websocket = False
    ws_config = None
    ws_user_info = None
    EmbySession = None
    MonitoredState = ''
    config_file = ''
    ws_lang = None
    wsock = None

    def stop(self):
        print('ws stop')
        self.stop_websocket = True
        self.ws.close()

    def __init__(self):
        self.emby_state = 'Init'
        threading.Thread.__init__(self)
        logging.info('Ws:Fin init\n')

    def thread_function_play(self, data, scripterx=False):
        print("Thread Play: starting")
        playto_file(self.EmbySession, data, scripterx)
        self.recargar_config()
        print("Thread Play: finishing")

    def set_lang(self, lang):
        self.ws_lang = lang
        self.EmbySession.lang = lang

    def recargar_config(self):
        if self.ws_config["DebugLevel"] > 0:
            print('Recargando Configuracion')
        with open(self.config_file, 'r') as f:
            config = json.load(f)
        f.close
        default = config.get("Autoscript", False)
        config["Autoscript"] = default
        default = config.get("enable_all_libraries", False)
        config["enable_all_libraries"] = default
        default = config.get("smbtrick", False)
        config["smbtrick"] = default
        self.ws_config = config
        self.EmbySession.config = config
        return (config)

    def _play(self, data):
        command = data['PlayCommand']
        if command == 'PlayNow':
            if self.EmbySession.playstate == "Loading" or self.EmbySession.playstate == "Replay":
                if self.ws_config["DebugLevel"] > 0:
                    print("Esta en la pantalla de Loading, tenemos que esperar")
                timeout = 60
                t = 0
                while self.EmbySession.playstate == "Loading" or t > timeout:
                    time.sleep(3)
                    t = t + 3
            if self.EmbySession.playstate == "Playing":
                if self.ws_config["DebugLevel"] > 0:
                    print("ya se esta reproduciendo algo")
                playother(self.EmbySession, data, False)
            else:
                x = threading.Thread(target=self.thread_function_play, args=(data,))
                x.start()

    def _general_commands(self, data):
        cmd = data['Name']
        args = data['Arguments']
        if cmd == 'SetAudioStreamIndex':
            params = self.EmbySession.process_data(self.EmbySession.currentdata)
            audio_index = self.EmbySession.get_xnoppo_audio_index(params["ControllingUserId"], params["item_id"], int(args['Index']))
            setaudiotrack(self.ws_config, audio_index)
            self.EmbySession.currentdata["AudioStreamIndex"] = int(args['Index'])
            self._report_progress_now()
        elif cmd == 'SetSubtitleStreamIndex':
            params = self.EmbySession.process_data(self.EmbySession.currentdata)
            subs_index = self.EmbySession.get_xnoppo_subs_index(params["ControllingUserId"], params["item_id"], int(args['Index']))
            setsubstrack(self.ws_config, subs_index)
            self.EmbySession.currentdata["SubtitleStreamIndex"] = int(args['Index'])
            self._report_progress_now()

    def _report_progress_now(self):
        # Immediately push the current playback state (with the just-changed
        # audio/subtitle index) to Emby. The 1s progress loop would eventually
        # do this, but setsubstrack/setaudiotrack block the receive thread for
        # several seconds, leaving the Emby app's now-playing UI stale so it
        # won't let the user re-select a track. Report at once to stay in sync.
        try:
            pt = json.loads(getplayingtime(self.ws_config))
            positionticks = int(pt.get("cur_time", 0)) * 10000000
            totalticks = int(pt.get("total_time", 0)) * 10000000
            self.EmbySession.playingprogress(self.EmbySession.currentdata, positionticks, totalticks, False, False)
        except Exception as e:
            logging.debug("report_progress_now failed: %s", e)

    def _check_state(self, data, sessions):
        if self.ws_config["MonitoredDevice"]:
            if sessions:
                print("=================================================================================")
                for item in data:
                    if item["DeviceId"] == self.ws_config["MonitoredDevice"]:
                        item_data = item
                        try:
                            item_data_list = self.EmbySession.get_item_info(item_data["UserId"], item_data["NowPlayingItem"]["Id"])
                            print("=========== ITEM DATA LIST ===============")
                            print(item_data_list)
                            print("=========== ITEM DATA LIST END ===============")
                            break
                        except:
                            break
            else:
                item_data = self.EmbySession.get_session_user_info(data["UserId"], self.ws_config["MonitoredDevice"])
            print(sessions)
            print(data)
            try:
                if item_data["NowPlayingItem"]:
                    if self.MonitoredState == '':
                        if self.ws_config["DebugLevel"] > 0:
                            print(item_data["DeviceName"])
                        if self.ws_config["DebugLevel"] > 0:
                            print(item_data["NowPlayingItem"]["Name"])
                        if self.ws_config["DebugLevel"] > 0:
                            print(item_data["NowPlayingItem"]["Container"])
                        self.MonitoredState = item_data["NowPlayingItem"]["Name"]
                        itemtype = item_data["NowPlayingItem"]["Type"]
                        item_id = item_data["NowPlayingItem"]["ParentId"]
                        item_name = item_data["NowPlayingItem"]["Name"]
                        if itemtype == "Episode":
                            item_lib_id = item_data["NowPlayingItem"]["Path"]
                        elif itemtype == "Movie":
                            item_lib_id = item_data["NowPlayingItem"]["Path"]
                        views_list = self.ws_config["Libraries"]
                        LibraryName = ""
                        encontrado = False
                        if self.ws_config["enable_all_libraries"]:
                            LibraryName = "All Libraries Enabled"
                            encontrado = True
                        else:
                            for view in views_list:
                                view_data = {}
                                if view["Active"] == True:
                                    view_data["Name"] = view["Name"]
                                    view_data["Id"] = view["Id"]
                                    encontrado = self.EmbySession.is_item_in_library2(view["Id"], item_lib_id)
                                    if encontrado:
                                        LibraryName = view_data["Name"]
                                        break
                        if encontrado:
                            if self.ws_config["DebugLevel"] > 0:
                                print("LIBRARY NAME: " + LibraryName)
                            logging.debug('El item %s es de la libreria %s', item_name, LibraryName)
                            if sessions:
                                userdata = item_data_list["UserData"]
                            else:
                                userdatalist = data["UserDataList"]
                                userdata = userdatalist[0]
                            try:
                                playstate = item_data["PlayState"]
                            except:
                                playstate = {}
                            data2 = {
                                "ItemIds": [int(item_data["NowPlayingItem"]["Id"])],
                                "StartIndex": 0,
                                "StartPositionTicks": userdata["PlaybackPositionTicks"],
                                "MediaSourceId": playstate.get("MediaSourceId", ""),
                                "AudioStreamIndex": playstate.get("AudioStreamIndex", 1),
                                "SubtitleStreamIndex": playstate.get("SubtitleStreamIndex", -1),
                                "ControllingUserId": item_data["UserId"],
                                "SessionID": item_data["Id"],
                                "DeviceName": item_data["DeviceName"],
                                "Device_Id": self.ws_config["MonitoredDevice"]
                            }
                            if self.ws_config["DebugLevel"] > 0:
                                print(data2)
                            timeout = 60
                            t = 0
                            while self.EmbySession.playstate == "Loading" or t > timeout:
                                time.sleep(3)
                                t = t + 3
                            if self.EmbySession.playstate == "Playing" or self.EmbySession.playstate == "Replay":
                                if self.ws_config["DebugLevel"] > 0:
                                    print("ya se esta reproduciendo algo")
                                playother(self.EmbySession, data2, True)
                            else:
                                x = threading.Thread(target=self.thread_function_play, args=(data2, True))
                                x.start()
                            return (0)
                        else:
                            if self.ws_config["DebugLevel"] > 0:
                                print('El item no es de ninguna libreria activa: ' + item_name)
                            logging.debug('El item %s no es de ninguna libreria activa', item_name)
                    elif item_data["NowPlayingItem"]["Name"] == self.MonitoredState:
                        if self.ws_config["DebugLevel"] > 0:
                            print('Continue Playing')
                        if self.ws_config["DebugLevel"] > 0:
                            print(item_data["DeviceName"])
                        if self.ws_config["DebugLevel"] > 0:
                            print(self.MonitoredState)
                        if self.ws_config["DebugLevel"] > 0:
                            print(item_data["NowPlayingItem"]["Name"])
                    else:
                        if self.ws_config["DebugLevel"] > 0:
                            print('Change Playing')
                        if self.ws_config["DebugLevel"] > 0:
                            print(item_data["DeviceName"])
                        if self.ws_config["DebugLevel"] > 0:
                            print(self.MonitoredState)
                        if self.ws_config["DebugLevel"] > 0:
                            print(item_data["NowPlayingItem"]["Name"])
            except:
                if self.MonitoredState != '':
                    if self.ws_config["DebugLevel"] > 0:
                        print('Stopped Playing')
                    if self.ws_config["DebugLevel"] > 0:
                        print(item_data["DeviceName"])
                    if self.ws_config["DebugLevel"] > 0:
                        print(self.MonitoredState)
                    self.MonitoredState = ''

    def _playstate(self, data):
        command = data['Command']
        if command == 'Stop':
            sendremotekey('STP', self.ws_config)
        elif command == 'Pause':
            sendremotekey('PAU', self.ws_config)
        elif command == 'Unpause':
            sendremotekey('PLA', self.ws_config)
        elif command == 'NextTrack':
            sendremotekey('NXT', self.ws_config)
        elif command == 'PreviousTrack':
            sendremotekey('PRE', self.ws_config)
        elif command == 'Seek':
            playticks = data["SeekPositionTicks"]
            setplaytime(self.ws_config, playticks)
        elif command == 'Rewind':
            sendremotekey('REV', self.ws_config)
        elif command == 'FastForward':
            sendremotekey('FWD', self.ws_config)
        elif command == 'PlayPause':
            sendremotekey('PAU', self.ws_config)

    # NOTE: websocket-client >= 0.58 passes the WebSocketApp instance as the
    # first positional arg to every callback. These are registered as bound
    # methods (self.on_*), so the signature is (self, wsapp, ...). The wsapp
    # arg is unused; we operate on the xnoppo_ws instance via self.
    def on_message(self, wsapp, msg):
        msg_json = json.loads(msg)
        msg_type = msg_json['MessageType']
        self.emby_state = "Message Arrived:" + msg_type
        # Sessions/UserDataChanged arrive ~once per second with very large
        # payloads; logging them in full bloats the log and makes it unreadable.
        # Log everything else (Play/Playstate/GeneralCommand) verbatim.
        if msg_type not in ('Sessions', 'UserDataChanged'):
            if self.ws_config["DebugLevel"] > 0:
                print("Ws:Message Arrived:" + msg)
                print(self.EmbySession.playstate)
            logging.debug("Ws:Message Arrived: %s", msg)
            logging.debug("Json Message: %s", msg_json)

        if msg_type == 'Play':
            data = msg_json['Data']
            self._play(data)

        elif msg_type == 'Playstate':
            data = msg_json['Data']
            self._playstate(data)

        elif msg_type == "UserDataChanged":
            data = msg_json['Data']

        elif msg_type == "LibraryChanged":
            data = msg_json['Data']

        elif msg_type == "GeneralCommand":
            data = msg_json['Data']
            self._general_commands(data)
        elif msg_type == "Sessions":
            data = msg_json['Data']
            self._check_state(data, True)
        else:
            logging.debug("WebSocket Message Type: %s", msg_type)

    def on_error(self, wsapp, error):
        if self.ws_config["DebugLevel"] > 0:
            print(error)
        logging.error("Ws:Error: %s", error)
        self.emby_state = 'Ws::Error'

    def on_close(self, wsapp, close_status_code=None, close_msg=None):
        if self.ws_config["DebugLevel"] > 0:
            print("Ws:Connection Closed")
        self.emby_state = 'Closed'

    def on_open(self, wsapp):
        if self.ws_config["DebugLevel"] > 0:
            print('Ws:Open')
        self.emby_state = 'Opened'
        b = self.wsock.send('{"MessageType":"SessionsStart", "Data": "0,1500"}')
        print(b)

    def run(self):
        self.EmbySession = EmbyHttp(self.ws_config)
        self.EmbySession.lang = self.ws_lang
        self.ws_user_info = self.EmbySession.user_info
        self.EmbySession.set_capabilities()
        uri = self.ws_config["emby_server"].replace('http://', 'ws://').replace('https://', 'wss://')
        uri = uri + '/?api_key=' + self.ws_user_info["AccessToken"] + '&deviceId=Xnoppo'
        if self.ws_config["DebugLevel"] > 0:
            print(uri)
        self.wsock = WebSocketApp(uri,
                                  on_open=self.on_open,
                                  on_message=self.on_message,
                                  on_error=self.on_error,
                                  on_close=self.on_close)
        if self.ws_config["DebugLevel"] > 0:
            print('Ws:Fin open ws\n')
        self.emby_state = 'Run'
        while not self.stop_websocket:
            # run_forever blocks until the connection drops. SessionsStart is
            # sent from on_open on (re)connect, so nothing to send here.
            self.wsock.run_forever(ping_interval=10)
            if self.ws_config["DebugLevel"] > 0:
                print("after run forever")
            if self.stop_websocket:
                break
            self.emby_state = 'On run_forever'
            # Connection dropped unexpectedly; back off briefly before retrying.
            time.sleep(5)

        if self.ws_config["DebugLevel"] > 0:
            print("WebSocketClient Stopped")
