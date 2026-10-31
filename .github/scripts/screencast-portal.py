"""A scripted org.freedesktop.portal.ScreenCast service for the capture tests (capture.yml).

It answers the portal's requests as xdg-desktop-portal does, with the Response signal of a request object whose
path is derived from the caller's unique name and handle token, and shares one PipeWire node: the one named on the
command line, which a GStreamer test source provides. OpenPipeWireRemote returns a descriptor of a fresh connection
to the PipeWire socket. The hosted runners have no GPU, and xdg-desktop-portal-wlr 0.7 refuses to share a wlroots
output that offers no DMA-BUF format, which a software-rendered compositor does not; this service stands in for it.

Usage: /usr/bin/python3 screencast-portal.py NODE_ID PIPEWIRE_SOCKET WIDTH HEIGHT
"""
import socket
import sys

import dbus
import dbus.mainloop.glib
import dbus.service
from gi.repository import GLib

SCREEN_CAST = 'org.freedesktop.portal.ScreenCast'
REQUEST = 'org.freedesktop.portal.Request'
SESSION = 'org.freedesktop.portal.Session'
PROPERTIES = 'org.freedesktop.DBus.Properties'
ROOT = '/org/freedesktop/portal/desktop'

dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
bus = dbus.SessionBus()
node, pipewire, width, height = int(sys.argv[1]), sys.argv[2], int(sys.argv[3]), int(sys.argv[4])


def sender_path(sender):
    return sender[1:].replace('.', '_')


class Request(dbus.service.Object):
    @dbus.service.signal(REQUEST, signature='ua{sv}')
    def Response(self, code, results):
        pass


class Session(dbus.service.Object):
    @dbus.service.method(SESSION, in_signature='', out_signature='')
    def Close(self):
        self.remove_from_connection()


class Portal(dbus.service.Object):
    def respond(self, sender, token, results):
        path = '%s/request/%s/%s' % (ROOT, sender_path(sender), token)
        request = Request(bus, path)

        def emit():
            request.Response(dbus.UInt32(0), dbus.Dictionary(results, signature='sv'))
            request.remove_from_connection()
            return False
        GLib.timeout_add(20, emit)
        return dbus.ObjectPath(path)

    @dbus.service.method(SCREEN_CAST, in_signature='a{sv}', out_signature='o', sender_keyword='sender')
    def CreateSession(self, options, sender=None):
        session = '%s/session/%s/%s' % (ROOT, sender_path(sender), options['session_handle_token'])
        Session(bus, session)
        return self.respond(sender, options['handle_token'], {'session_handle': dbus.String(session, variant_level=1)})

    @dbus.service.method(SCREEN_CAST, in_signature='oa{sv}', out_signature='o', sender_keyword='sender')
    def SelectSources(self, session, options, sender=None):
        if int(options.get('types', 1)) & 1 == 0:
            raise dbus.exceptions.DBusException('only monitors are shared')
        return self.respond(sender, options['handle_token'], {})

    @dbus.service.method(SCREEN_CAST, in_signature='osa{sv}', out_signature='o', sender_keyword='sender')
    def Start(self, session, parent, options, sender=None):
        properties = dbus.Dictionary({
            'position': dbus.Struct((dbus.Int32(0), dbus.Int32(0)), signature='ii', variant_level=1),
            'size': dbus.Struct((dbus.Int32(width), dbus.Int32(height)), signature='ii', variant_level=1),
            'source_type': dbus.UInt32(1, variant_level=1),
        }, signature='sv')
        streams = dbus.Array([dbus.Struct((dbus.UInt32(node), properties), signature='ua{sv}')], signature='(ua{sv})',
                             variant_level=1)
        return self.respond(sender, options['handle_token'], {'streams': streams})

    @dbus.service.method(SCREEN_CAST, in_signature='oa{sv}', out_signature='h')
    def OpenPipeWireRemote(self, session, options):
        connection = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        connection.connect(pipewire)
        return dbus.types.UnixFd(connection)

    @dbus.service.method(PROPERTIES, in_signature='ss', out_signature='v')
    def Get(self, interface, name):
        values = {'AvailableSourceTypes': 1, 'AvailableCursorModes': 1 | 2, 'version': 4}
        if interface != SCREEN_CAST or name not in values:
            raise dbus.exceptions.DBusException('no property %s.%s' % (interface, name))
        return dbus.UInt32(values[name], variant_level=1)


name = dbus.service.BusName('org.freedesktop.portal.Desktop', bus)
Portal(bus, ROOT)
print('screen cast portal ready, sharing node %d' % node, flush=True)
GLib.MainLoop().run()
