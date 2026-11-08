"""Starts a screen cast of a headless mutter with the pointer sent as metadata, for the capture tests (capture.yml).

It uses mutter's own org.gnome.Mutter.ScreenCast API, which asks nobody, inside a remote desktop session, whose virtual
pointer gives the headless compositor a pointer to show, and keeps the pointer moving between two places so that the
stream never goes idle. The stream's PipeWire node id is written to the file named on the command line.

Usage: /usr/bin/python3 mutter-screencast.py NODE_FILE
"""
import sys

import dbus
import dbus.mainloop.glib
from gi.repository import GLib

SCREEN_CAST = 'org.gnome.Mutter.ScreenCast'
REMOTE_DESKTOP = 'org.gnome.Mutter.RemoteDesktop'
METADATA_CURSOR = dbus.UInt32(2)

dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
bus = dbus.SessionBus()
remote_path = bus.get_object(REMOTE_DESKTOP, '/org/gnome/Mutter/RemoteDesktop').get_dbus_method(
    'CreateSession', REMOTE_DESKTOP)()
remote_object = bus.get_object(REMOTE_DESKTOP, remote_path)
remote = dbus.Interface(remote_object, REMOTE_DESKTOP + '.Session')
session_id = remote_object.Get(REMOTE_DESKTOP + '.Session', 'SessionId', dbus_interface='org.freedesktop.DBus.Properties')
cast_path = bus.get_object(SCREEN_CAST, '/org/gnome/Mutter/ScreenCast').get_dbus_method('CreateSession', SCREEN_CAST)(
    dbus.Dictionary({'remote-desktop-session-id': session_id}, signature='sv'))
cast = dbus.Interface(bus.get_object(SCREEN_CAST, cast_path), SCREEN_CAST + '.Session')
stream_path = cast.RecordMonitor('', dbus.Dictionary({'cursor-mode': METADATA_CURSOR}, signature='sv'))
stream = dbus.Interface(bus.get_object(SCREEN_CAST, stream_path), SCREEN_CAST + '.Stream')


def added(node):
    with open(sys.argv[1], 'w') as file:
        file.write(str(int(node)))
    print('mutter stream node %d' % int(node), flush=True)


stream.connect_to_signal('PipeWireStreamAdded', added)
remote.Start()
places = [(640.0, 360.0), (660.0, 380.0)]
turn = [0]


def move():
    turn[0] += 1
    x, y = places[turn[0] % 2]
    remote.NotifyPointerMotionAbsolute(stream_path, dbus.Double(x), dbus.Double(y))
    return True


GLib.timeout_add(250, move)
GLib.MainLoop().run()
