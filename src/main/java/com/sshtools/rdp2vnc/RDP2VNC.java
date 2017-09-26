package com.sshtools.rdp2vnc;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.URL;
import java.util.prefs.Preferences;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sshtools.javardp.AbstractContext;
import com.sshtools.javardp.ConnectionException;
import com.sshtools.javardp.DefaultCredentialsProvider;
import com.sshtools.javardp.IContext;
import com.sshtools.javardp.RdesktopDisconnectException;
import com.sshtools.javardp.RdesktopException;
import com.sshtools.javardp.SecurityType;
import com.sshtools.javardp.State;
import com.sshtools.javardp.client.Rdesktop;
import com.sshtools.javardp.graphics.RdesktopCanvas;
import com.sshtools.javardp.io.DefaultIO;
import com.sshtools.javardp.keymapping.KeyCode_FileBased;
import com.sshtools.javardp.keymapping.KeyMapException;
import com.sshtools.javardp.layers.Rdp;
import com.sshtools.javardp.rdp5.VChannels;
import com.sshtools.javardp.rdp5.cliprdr.ClipChannel;
import com.sshtools.rfbserver.DisplayDriver;
import com.sshtools.rfbserver.RFBClient;
import com.sshtools.rfbserver.RFBServer;
import com.sshtools.rfbserver.RFBServerConfiguration;
import com.sshtools.rfbserver.drivers.CopyRectDisplayDriver;
import com.sshtools.rfbserver.drivers.WindowedDisplayDriver;
import com.sshtools.rfbserver.encodings.authentication.None;
import com.sshtools.rfbserver.encodings.authentication.Tight;
import com.sshtools.rfbserver.encodings.authentication.VNC;
import com.sshtools.rfbserver.transport.RFBServerTransportFactory;
import com.sshtools.rfbserver.transport.ServerSocketRFBServerTransportFactory;
import com.sshtools.rfbserver.transport.SocketRFBServerTransportFactory;

public class RDP2VNC implements RFBServerConfiguration {
	public enum Mode {
		listen, reverse
	}

	private static final char OPT_HELP = '?';
	private static final char OPT_MODE = 'm';
	private static final char OPT_SIZE = 's';
	private static final char OPT_LISTEN_ADDRESS = 'L';
	private static final char OPT_FULL_SCREEN = 'f';
	private static final char OPT_VIEWPORT = 'v';
	private static final char OPT_NO_COPY_RECT = 'N';
	private static final char OPT_DESKTOP_NAME = 'k';
	private static final char OPT_BACKLOG = 'b';
	private static final char OPT_VNC_PASSWORD = 'w';
	private static final char OPT_VNC_PASSWORD_FILE = 'W';
	private static final char OPT_LOG_LEVEL = 'l';
	private static final char OPT_DOMAIN = 'd';
	private static final char OPT_USERNAME = 'u';
	private static final char OPT_PASSWORD = 'p';
	private static final char OPT_KEYMAP = 'k';
	private static final char OPT_NO_SSL = 'S';
	private static final char OPT_NO_NLA = 'A';
	private static final char OPT_STAY_CONNECTED = 'Y';
	private static final char OPT_RETRY_TIME = 'R';
	private static final char OPT_NO_PACKET_ENCRYPTION = 'E';
	private static final char OPT_CONSOLE = 'C';
	private static final char OPT_COMMAND = 'c';
	private static final char OPT_DIRECTORY = 'D';
	private static final char OPT_TIGHT_AUTH = 't';
	private static final char OPT_4 = '4';
	private static final char OPT_BPP = 'B';
	private static final char OPT_DEBUG_HEX = 'H';
	static Logger LOG;
	private CommandLine cli;
	private DisplayDriver driver;
	private String desktopName = "SSHTools Java RDP to VNC Proxy";
	private Mode mode = Mode.listen;
	private RFBServerTransportFactory serverTransportFactory;
	private RDPDisplayDriver underlyingDriver;
	private String address = "localhost";
	private int port = 3389;
	private String listenAddress = "localhost";
	private int listenPort = 5900;
	private int backlog;
	private RFBServer server;
	private char[] vncPassword;
	private int imageType = BufferedImage.TYPE_INT_ARGB;
	private int width;
	private int height;

	public RDP2VNC() {
	}

	protected void createOptions() {
	}

	protected void addOptions(Options options) {
		options.addOption(new Option(String.valueOf(OPT_HELP), "help", false, "Display help"));
		options.addOption(new Option(String.valueOf(OPT_MODE), "mode", true,
				"Connection mode. May either be 'listen' (the default), or 'reverse' for to connect to a VNC viewer running in listen mode"));
		options.addOption(new Option("e", "encodings", true, "Comma separated list of enabled encoding"));
		options.addOption(new Option(String.valueOf(OPT_NO_COPY_RECT), "nocopyrect", false,
				"Do not use the CopyRect driver for window movement (if supported)"));
		options.addOption(new Option(String.valueOf(OPT_LOG_LEVEL), "log", true, "Log level"));
		options.addOption(new Option(String.valueOf(OPT_SIZE), "size", true,
				"The size of remote desktop to request from the RDP server (defaults to 800x600). Use the format <Width>,<Height>"));
		options.addOption(new Option(String.valueOf(OPT_LISTEN_ADDRESS), "listen-address", true,
				"The address and port to listen on in the format <Address>[:<Port>]. Address defaults to 0.0.0.0 (all interfaces) and port "
						+ "defaults to 3389"));
		options.addOption(new Option(String.valueOf(OPT_FULL_SCREEN), "full-screen", false,
				"Request full screen desktop from the remote server (it's native resolution)."));
		options.addOption(new Option(String.valueOf(OPT_VIEWPORT), "viewport", true,
				"Serve only a rectangular viewport of the entire desktop.Use the format " + "<X>,<Y>,<Width>,<Height>."));
		options.addOption(new Option(String.valueOf(OPT_DESKTOP_NAME), "desktop", true,
				"The desktop name. Some VNC viewers may display this. Can be any text, and defaults to the RDP server name."));
		options.addOption(new Option(String.valueOf(OPT_VNC_PASSWORD), "vnc-password", true,
				"The password that VNC clients must authenticate with. NOTE, this is not the Windows "
						+ "password used to authenticate with the RDP server."));
		options.addOption(new Option(String.valueOf(OPT_VNC_PASSWORD_FILE), "vnc-passwordfile", true,
				"A file containing the VNC password that clients must authenticate with. NOTE, this is not the Windows "
						+ "password used to authenticate with the RDP server."));
		options.addOption(new Option(String.valueOf(OPT_BACKLOG), "backlog", true,
				"Maximum number of incoming connections that are allowed. Only applies in 'listen' mode"));
		options.addOption(new Option(String.valueOf(OPT_DOMAIN), "domain", true,
				"The optional windows domain to authenticate with. If not supplied, the default will be used."));
		options.addOption(new Option(String.valueOf(OPT_USERNAME), "username", true,
				"The optional windows username to authenticate with. If not supplied, the user will be prompted."));
		options.addOption(new Option(String.valueOf(OPT_PASSWORD), "password", true,
				"The optional windows password to authenticate with. If not supplied, the user will be prompted."));
		options.addOption(new Option(String.valueOf(OPT_KEYMAP), "keymap", true, "The keyboard map. Defaults to en-us."));
		options.addOption(new Option(String.valueOf(OPT_NO_SSL), "no-ssl", false,
				"Disable SSL encryption between the RDP2VNC server and the RDP target (implies --no-nla)."));
		options.addOption(new Option(String.valueOf(OPT_NO_NLA), "no-nla", false,
				"Disable usage of Network Level Authentication (NLA) the RDP2VNC server and the RDP target."));
		options.addOption(new Option(String.valueOf(OPT_NO_PACKET_ENCRYPTION), "no-packet-encryption", false,
				"Display packet encryption between the RDP2VNC server and the RDP target."));
		options.addOption(new Option(String.valueOf(OPT_CONSOLE), "console", false, "Connect to the target RDP server's console."));
		options.addOption(
				new Option(String.valueOf(OPT_COMMAND), "command", true, "Run this command upon logon to the target RDP server."));
		options.addOption(new Option(String.valueOf(OPT_DIRECTORY), "directory", true,
				"Directory to be placed in upon logon to the target RDP server."));
		options.addOption(new Option(String.valueOf(OPT_STAY_CONNECTED), "stay-connected", false,
				"Keep trying to connect to the RDP server if it cannot be contacted. See also --retry-time."));
		options.addOption(new Option(String.valueOf(OPT_RETRY_TIME), "retry-time", true,
				"How often to try and reconnect to the RDP when a connection is lost or cannot be made."));
		options.addOption(new Option(String.valueOf(OPT_TIGHT_AUTH), "tight-authentication", false,
				"Enabled tight authentication (also presents capabilities). This is disabled by default for compatibility."));
		options.addOption(new Option(String.valueOf(OPT_4), "4", false, "Use RDP version 4 only."));
		options.addOption(new Option(String.valueOf(OPT_BPP), "bpp", false,
				"Colour depth in bits per pixel for RDP connection. By default the VNC connection will be matched to this."));
		options.addOption(new Option(String.valueOf(OPT_DEBUG_HEX), "debug-hex", false,
				"Output hexdumps of packets that arrive and are sent."));
	}

	protected IContext createRDPContext(final State state) {
		return new AbstractContext() {
			@Override
			public void toggleFullScreen() {
				// TODO pass this on to VNC?
			}

			@Override
			public void setLoggedOn() {
			}

			@Override
			public void screenResized(int width, int height, boolean clientInitiated) {
				// TODO pass this on to VNC
			}

			@Override
			public void error(Exception e, boolean sysexit) {
				if (sysexit)
					LOG.error("An error occured occured in the display.", e);
				else {
					LOG.error("An error occured occured in the display and the connection will be closed.", e);
				}
			}

			@Override
			public void dispose() {
				// TODO close the VNC connection
			}

			@Override
			public byte[] loadLicense() throws IOException {
				Preferences prefs = Preferences.userNodeForPackage(this.getClass());
				return prefs.getByteArray("licence." + state.getWorkstationName(), null);
			}

			@Override
			public void saveLicense(byte[] license) throws IOException {
				Preferences prefs = Preferences.userNodeForPackage(this.getClass());
				prefs.putByteArray("licence." + state.getWorkstationName(), license);
			}

			@Override
			public void ready(ReadyType ready) {
			}
		};
	}

	protected void start() throws Exception {
		com.sshtools.javardp.Options options = createRDPOptions();
		State state = new State(options);
		IContext ctx = createRDPContext(state);
		/*
		 * Create a canvas. This actually draws to the RFB servers display
		 * buffer and fires damage events when rectangles are painted. This
		 * means we don't need to do any polling in the RFB server itself as the
		 * problem is solved on the target RDP server itself.
		 */
		RdesktopCanvas canvas = new RdesktopCanvas(ctx, state, underlyingDriver);
		VChannels channels = new VChannels(state);
		ClipChannel clipChannel = new ClipChannel();
		if (state.isRDP5()) {
			if (options.isMapClipboard()) {
				try {
					channels.register(clipChannel);
					underlyingDriver.setClipChannel(clipChannel);
				} catch (RdesktopException rde) {
					throw new IOException("Could not initialise clip channel.");
				}
			}
			// DisplayControlChannel dcc = new DisplayControlChannel(ctx,
			// options);
			// try {
			// channels.register(dcc);
			// underlyingDriver.setDisplayControlChannel(dcc);
			// } catch (RdesktopException rde) {
			// throw new IOException("Could not initialise display control
			// channel.");
			// }
		}
		// canvas.addFocusListener(clipChannel);
		// Configure a keyboard layout
		Rdp rdpLayer = new Rdp(ctx, state, channels);
		LOG.info("Connecting to " + address + ":" + port + " ...");
		try {
			DefaultCredentialsProvider dcp = new DefaultCredentialsProvider();
			dcp.setUsername(this.cli.getOptionValue(OPT_USERNAME));
			String passwordStr = this.cli.getOptionValue(OPT_PASSWORD);
			dcp.setPassword(passwordStr == null || passwordStr.length() == 0 ? null : passwordStr.toCharArray());
			dcp.setDomain(nonBlank(this.cli.getOptionValue(OPT_DOMAIN)));
			rdpLayer.connect(new DefaultIO(InetAddress.getByName(address), port), dcp, options.getCommand(),
					options.getDirectory());
			LOG.info("Connection successful");
			/* Initialise the driver */
			try {
				driver.init();
			} catch (Exception e1) {
				throw new IOException("Driver initialisation problem.", e1);
			}
			/* Start the RFB server */
			server = new RFBServer(this, driver) {
				@Override
				protected RFBClient createClient() {
					RFBClient c = super.createClient();
					return c;
				}
			};
			if (!cli.hasOption(OPT_TIGHT_AUTH)) {
				if (vncPassword != null) {
					server.getSecurityHandlers().add(new VNC() {
						@Override
						protected char[] getPassword() {
							return vncPassword;
						}
					});
				}
			} else {
				Tight tight = new Tight();
				tight.getAuthenticationMethods().add(new None());
				if (vncPassword != null) {
					tight.getAuthenticationMethods().add(new VNC() {
						@Override
						protected char[] getPassword() {
							return vncPassword;
						}
					});
				}
				server.getSecurityHandlers().add(tight);
			}
			server.init(serverTransportFactory);
			new Thread("RFBServer") {
				public void run() {
					try {
						server.start();
					} catch (IOException e) {
						LOG.error("RFB Server exited with failure.", e);
					}
				}
			}.start();
			/* Run RDP loop */
			try {
				rdpLayer.mainLoop();
			} catch (RdesktopDisconnectException rde) {
				LOG.error("Connection terminated: " + rde.getMessage(), rde);
			}
		} catch (ConnectionException e) {
			throw new IOException(e.getMessage(), e);
		} catch (SocketException s) {
			throw s;
		} catch (RdesktopException e) {
			throw new IOException("Desktop protocol problem.", e);
		} finally {
			if (server != null && server.isStarted())
				server.stop();
		}
	}

	protected KeyCode_FileBased createKeymap(com.sshtools.javardp.Options options) throws IOException {
		// Keymap
		KeyCode_FileBased keyMap = null;
		String mapFile = cli.getOptionValue(OPT_KEYMAP);
		if (mapFile == null || mapFile.length() == 0)
			mapFile = "en-us";
		String keyMapPath = "keymaps/";
		URL resource = Rdesktop.class.getResource("/" + keyMapPath + mapFile);
		InputStream istr = resource.openStream();
		try {
			if (istr == null) {
				LOG.info("Loading keymap from filename");
				keyMap = new KeyCode_FileBased(options, keyMapPath + mapFile);
			} else {
				LOG.info("Loading keymap from InputStream");
				keyMap = new KeyCode_FileBased(options, resource, istr);
			}
		} catch (KeyMapException kme) {
			throw new IOException("Keymap error.", kme);
		} finally {
			if (istr != null)
				istr.close();
		}
		return keyMap;
	}

	protected com.sshtools.javardp.Options createRDPOptions() throws IOException {
		com.sshtools.javardp.Options options = new com.sshtools.javardp.Options();
		// Configure other options
		options.setWidth(width);
		options.setHeight(height);
		options.setPacketEncryption(!cli.hasOption(OPT_NO_PACKET_ENCRYPTION));
		int bpp = 16;
		if (cli.hasOption(OPT_BPP)) {
			bpp = Integer.parseInt(cli.getOptionValue(OPT_BPP));
		}
		options.setBpp(bpp);
		options.setRdp5(!cli.hasOption(OPT_4));
		if (cli.hasOption(OPT_NO_SSL)) {
			for (SecurityType t : SecurityType.supported()) {
				if (t.isSSL()) {
					options.getSecurityTypes().remove(t);
				}
			}
		}
		if (cli.hasOption(OPT_NO_NLA)) {
			for (SecurityType t : SecurityType.supported()) {
				if (t.isNLA()) {
					options.getSecurityTypes().remove(t);
				}
			}
		}
//		options.getSecurityTypes().remove(SecurityType.STANDARD);
		options.setConsoleSession(cli.hasOption(OPT_CONSOLE));
		options.setDirectory(nonBlank(cli.getOptionValue(OPT_DIRECTORY)));
		options.setCommand(nonBlank(cli.getOptionValue(OPT_COMMAND)));
		options.setFullscreen(cli.hasOption(OPT_FULL_SCREEN));
		options.setDebugHexdump(cli.hasOption(OPT_DEBUG_HEX));
		options.setKeymap(createKeymap(options));
		// TODO
		options.setRemapHash(true);
		options.setAltkeyQuiet(false);
		options.setLoadLicence(false);
		options.setSaveLicence(false);
		options.setLowLatency(false);
		return options;
	}

	protected int parseArguments(String[] args) throws IOException {
		Options options = new Options();
		addOptions(options);
		Parser parser = new GnuParser();
		try {
			cli = parser.parse(options, args);
			// Debug level
			String level = "warn";
			if (cli.hasOption(OPT_LOG_LEVEL)) {
				level = cli.getOptionValue(OPT_LOG_LEVEL);
			}
			System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", level);
			LOG = LoggerFactory.getLogger(RDP2VNC.class);
			// Help?
			if (cli.hasOption(OPT_HELP)) {
				printHelp(options);
				return -1;
			}
			width = 800;
			height = 600;
			if (cli.hasOption(OPT_SIZE)) {
				String size = cli.getOptionValue(OPT_VIEWPORT);
				String[] rec = size.split(",");
				if (rec.length != 2)
					throw new ParseException("Invalid size. Must be in format <Width>,<Height>");
				width = Integer.parseInt(rec[0]);
				height = Integer.parseInt(rec[1]);
			}
			// Determine driver
			underlyingDriver = new RDPDisplayDriver(width, height, BufferedImage.TYPE_INT_RGB);
			// Set view port
			if (cli.hasOption(OPT_VIEWPORT)) {
				WindowedDisplayDriver windowedDriver = new WindowedDisplayDriver(driver);
				try {
					String viewport = cli.getOptionValue(OPT_VIEWPORT);
					if (viewport.indexOf(',') != -1) {
						String[] rec = viewport.split(",");
						if (rec.length != 4) {
							throw new NumberFormatException();
						}
						windowedDriver.setArea(new Rectangle(Integer.parseInt(rec[0]), Integer.parseInt(rec[1]),
								Integer.parseInt(rec[2]), Integer.parseInt(rec[3])));
					} else {
						windowedDriver.setMonitor(Integer.parseInt(viewport));
					}
				} catch (NumberFormatException nfe) {
					throw new ParseException(
							"Viewport must either be a single monitor number or a string specifying the bounds of the viewport in the format <X>,<Y>,<Width>,<Height>");
				}
				driver = windowedDriver;
			} else
				driver = underlyingDriver;
			// Add the CopyRect driver
			if (!cli.hasOption(OPT_NO_COPY_RECT)) {
				driver = new CopyRectDisplayDriver(driver);
			}
			// Listen mode
			if (cli.hasOption(OPT_MODE)) {
				try {
					mode = Mode.valueOf(cli.getOptionValue(OPT_MODE));
				} catch (Exception e) {
					throw new ParseException("Invalid mode. May be one of " + toCommaSeparatedString((Object[]) Mode.values()));
				}
			}
			switch (mode) {
			case reverse:
				serverTransportFactory = new SocketRFBServerTransportFactory();
			default:
				serverTransportFactory = new ServerSocketRFBServerTransportFactory();
				break;
			}
			serverTransportFactory.init(this);
			// Other options
			if (cli.hasOption(OPT_DESKTOP_NAME)) {
				desktopName = cli.getOptionValue(OPT_DESKTOP_NAME);
			}
			// Backlog
			if (cli.hasOption(OPT_BACKLOG)) {
				try {
					backlog = Integer.parseInt(cli.getOptionValue(OPT_BACKLOG));
				} catch (NumberFormatException nfe) {
					throw new ParseException("Invalid backlog.");
				}
			}
			// Password
			if (cli.hasOption(OPT_VNC_PASSWORD_FILE)) {
				BufferedReader in = new BufferedReader(new FileReader(cli.getOptionValue(OPT_VNC_PASSWORD_FILE)));
				try {
					vncPassword = in.readLine().toCharArray();
				} finally {
					in.close();
				}
			} else if (cli.hasOption(OPT_VNC_PASSWORD)) {
				vncPassword = cli.getOptionValue(OPT_VNC_PASSWORD).toCharArray();
			}
			// Listen address (where VNC clients connect)
			String listenAddressStr = cli.getOptionValue(OPT_LISTEN_ADDRESS);
			if (listenAddressStr == null || listenAddressStr.length() == 0) {
				listenAddress = "0.0.0.0";
				listenPort = 5900;
			} else {
				try {
					// May just be a port number
					listenPort = Integer.parseInt(listenAddressStr);
					listenAddress = "0.0.0.0";
				} catch (NumberFormatException nfe) {
					listenAddress = listenAddressStr;
					listenPort = 5900;
					int idx = listenAddress.indexOf(':');
					if (idx != -1) {
						try {
							listenPort = Integer.parseInt(address.substring(idx + 1));
						} catch (NumberFormatException nfe2) {
							throw new ParseException("Invalid port number.");
						}
						listenAddress = address.substring(0, idx);
					}
				}
			}
			// Parse remaining arguments for target RDP server
			String[] remainingArgs = cli.getArgs();
			if (remainingArgs.length > 1) {
				throw new ParseException("Expected at most a single argument containing [<address>][:port]");
			} else if (remainingArgs.length == 0) {
				address = "127.0.0.1";
				port = 3389;
			} else {
				try {
					// May just be a port number
					port = Integer.parseInt(remainingArgs[0]);
					address = "127.0.0.1";
				} catch (NumberFormatException nfe) {
					address = remainingArgs[0];
					port = 3389;
					int idx = address.indexOf(':');
					if (idx != -1) {
						try {
							port = Integer.parseInt(address.substring(idx + 1));
						} catch (NumberFormatException nfe2) {
							throw new ParseException("Invalid port number.");
						}
						address = address.substring(0, idx);
					}
				}
			}
			if (address.equalsIgnoreCase("localhost"))
				address = "127.0.0.1";
			// Output some info about the options chosen
			LOG.info("Driver: " + driver);
			LOG.info("Transport: " + serverTransportFactory.getClass().getSimpleName());
			return 0;
		} catch (ParseException pe) {
			System.err.println(getClass().getName() + ": " + pe.getMessage() + " Use -? or --help for more information.");
			return 2;
		}
	}

	private void printHelp(Options options) {
		HelpFormatter formatter = new HelpFormatter();
		formatter.printHelp(getClass().getSimpleName(), "A server to allow connections to RDP servers from VNC clients", options,
				"Provided by SSHTOOLS Limited.", true);
	}

	public static void main(String[] args) throws Exception {
		RDP2VNC server = new RDP2VNC();
		int result = server.parseArguments(args);
		if (result == 0) {
			server.start();
		} else if (result > 0) {
			System.exit(result);
		}
	}

	public int getPort() {
		return listenPort;
	}

	public int getListenBacklog() {
		return backlog;
	}

	public String getAddress() {
		return listenAddress;
	}

	public String getDesktopName() {
		return desktopName;
	}

	private static String toCommaSeparatedString(Object... objects) {
		StringBuilder bui = new StringBuilder();
		for (Object o : objects) {
			if (bui.length() > 0) {
				bui.append(",");
			}
			bui.append(String.valueOf(o));
		}
		return bui.toString();
	}

	private static String nonBlank(String str) {
		return str == null ? "" : str;
	}

	public int getImageType() {
		return imageType;
	}
}
