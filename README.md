# rdp2vnc

This library makes use of SSHTools' RDP component (originally based on properoRDP) and our own
Java RFB/VNC server.

It consists of a single 'Display Driver' implementation that plugs in to the RFB server and makes a
connection to a remote RDP server using an in-memory backing store. This backing store is then
served over VNC to any remote clients.

Mouse and keyboard events from clients are also sent to the RDP server, and there is some support
for other control events such as desktop resizing.

In short, it is an RDP-to-VNC bridge.

## Driver

The SSHTools RFB Server driver is *com.sshtools.rdp2vnc.RDPDisplayDriver*. You will most definitely
need to study the Example below for how to build it into your own application. The driver has
no particular requirements on how the I/O is done between the RFB server and your viewers, and
the RDP client and it's target host.

The general idea is :-

 * You start an RFB server using this 'Driver'
 * You start an RDP client connection using the same driver instance as the 'Display'
 * You connect the I/O streams of each to each other
 

## Standalone application and Example 
Included in the library is a simple command line driven standalone application that serves as both
as an example, and as a useful application if you want to access Windows hosts using a VNC client
(this is deal for the HTML5 based noVNC) without installing a VNC service on the target server.

The example will set up a TCP/IP server on port 5900 (by default) and connect to port 3389. You 
can then connect any VNC viewer to port 5900 and should see the desktop being served on port 3389. 

By using --help, the following will be displayed detailing all possible options.

```
usage: RDP2VNC [-?] [-b <arg>] [-C] [-c <arg>] [-d <arg>] [-D <arg>] [-e
       <arg>] [-E] [-f] [-k <arg>] [-l <arg>] [-L <arg>] [-m <arg>] [-N]
       [-p <arg>] [-s <arg>] [-S] [-v <arg>] [-w <arg>] [-W <arg>]
A server to allow connections to RDP servers from VNC clients
 -?,--help                     Display help
 -b,--backlog <arg>            Maximum number of incoming connections that
                               are allowed. Only applies in 'listen' mode
 -C,--console                  Connect to the target RDP server's console.
 -c,--command <arg>            Run this command upon logon to the target
                               RDP server.
 -d,--username <arg>           The optional windows username to
                               authenticate with. If not supplied, the
                               user will be prompted.
 -D,--directory <arg>          Directory to be placed in upon logon to the
                               target RDP server.
 -e,--encodings <arg>          Comma separated list of enabled encoding
 -E,--packet-encryption        Enable packet encryption between the
                               RDP2VNC server and the RDP target.
 -f,--full-screen              Request full screen desktop from the remote
                               server (it's native resolution).
 -k,--keymap <arg>             The keyboard map. Defaults to en-us.
 -l,--log <arg>                Log level
 -L,--listen-address <arg>     The address and port to listen on in the
                               format <Address>[:<Port>]. Address defaults
                               to 0.0.0.0 (all interfaces) and port
                               defaults to 3389
 -m,--mode <arg>               Connection mode. May either be 'listen'
                               (the default), or 'reverse' for to connect
                               to a VNC viewer running in listen mode
 -N,--nocopyrect               Do not use the CopyRect driver for window
                               movement (if supported)
 -p,--password <arg>           The optional windows password to
                               authenticate with. If not supplied, the
                               user will be prompted.
 -s,--size <arg>               The size of remote desktop to request from
                               the RDP server (defaults to 800x600). Use
                               the format <Width>,<Height>
 -S,--ssl                      Enable SSL encryption between the RDP2VNC
                               server and the RDP target.
 -v,--viewport <arg>           Serve only a rectangular viewport of the
                               entire desktop.Use the format
                               <X>,<Y>,<Width>,<Height>.
 -w,--vnc-password <arg>       The password that VNC clients must
                               authenticate with. NOTE, this is not the
                               Windows password used to authenticate with
                               the RDP server.
 -W,--vnc-passwordfile <arg>   A file containing the VNC password that
                               clients must authenticate with. NOTE, this
                               is not the Windows password used to
                               authenticate with the RDP server.

Provided by SSHTOOLS Limited.

```



 
