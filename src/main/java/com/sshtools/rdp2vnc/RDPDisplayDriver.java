package com.sshtools.rdp2vnc;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.util.ArrayList;
import java.util.EventListener;
import java.util.List;

import javax.swing.event.EventListenerList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sshtools.javardp.graphics.Display;
import com.sshtools.javardp.graphics.RdesktopCanvas;
import com.sshtools.javardp.graphics.RdpCursor;
import com.sshtools.javardp.rdp5.cliprdr.ClipChannel;
import com.sshtools.javardp.rdp5.display.DisplayControlChannel;
import com.sshtools.javardp.rdp5.display.MonitorLayout;
import com.sshtools.rfbcommon.RFBConstants;
import com.sshtools.rfbcommon.ScreenData;
import com.sshtools.rfbcommon.ScreenDetail;
import com.sshtools.rfbcommon.ScreenDimension;
import com.sshtools.rfbserver.DisplayDriver;
import com.sshtools.rfbserver.RFBClient;
import com.sshtools.rfbserver.drivers.AbstractDisplayDriver;

/**
 * This class is both an implementation of an RFB server {@link DisplayDriver}
 * and and RDP viewer {@link Display}, gluing the two together.
 */
public class RDPDisplayDriver extends AbstractDisplayDriver implements Display {
	final static Logger LOG = LoggerFactory.getLogger(RDPDisplayDriver.class);
	private IndexColorModel cm = null;
	private BufferedImage bi = null;
	private EventListenerList uiListeners = new EventListenerList();
	private ClipChannel clipChannel;
	private DisplayControlChannel displayControlChannel;
	private RdesktopCanvas canvas;
	private PointerShape pointer;
	private Clipboard clipboard;
	private int keyMods;
	private Component fakeComponent;
	private int buttonMask;
	private static int seq = 0;

	public RDPDisplayDriver(int width, int height, int type) {
		this(width, height, type, null);
	}

	@SuppressWarnings("serial")
	public RDPDisplayDriver(int width, int height, int type, IndexColorModel cm) {
		fakeComponent = new Component() {
		};
		bi = new BufferedImage(width, height, type);
		this.cm = cm;
		clipboard = new Clipboard("RDPClient" + (++seq));
		pointer = new PointerShape();
		clearCursor();
	}

	public DisplayControlChannel getDisplayControlChannel() {
		return displayControlChannel;
	}

	public void setDisplayControlChannel(DisplayControlChannel displayControlChannel) {
		this.displayControlChannel = displayControlChannel;
	}

	public ClipChannel getClipChannel() {
		return clipChannel;
	}

	public void setClipChannel(ClipChannel clipChannel) {
		this.clipChannel = clipChannel;
	}

	@Override
	public int getDisplayWidth() {
		return bi.getWidth();
	}

	@Override
	public int getDisplayHeight() {
		return bi.getHeight();
	}

	@Override
	public BufferedImage getBufferedImage() {
		return bi;
	}

	@Override
	public Graphics getDisplayGraphics() {
		return bi.getGraphics();
	}

	@Override
	public BufferedImage getSubimage(int x, int y, int width, int height) {
		return bi.getSubimage(x, y, width, height);
	}

	/**
	 * Force a colour to its true RGB representation (extracting from colour
	 * model if indexed colour)
	 * 
	 * @param color
	 * @return
	 */
	@Override
	public int checkColor(int color) {
		if (cm != null)
			return cm.getRGB(color);
		return color;
	}

	@Override
	public void setIndexColorModel(IndexColorModel cm) {
		this.cm = cm;
	}

	@Override
	public void setRGB(int x, int y, int color) {
		// if(x >= bi.getWidth() || x < 0 || y >= bi.getHeight() || y < 0)
		// return;
		if (cm != null)
			color = cm.getRGB(color);
		bi.setRGB(x, y, color);
	}

	@Override
	public void setRGBNoConversion(int x, int y, int cx, int cy, int[] data, int offset, int w) {
		bi.setRGB(x, y, cx, cy, data, offset, w);
	}

	@Override
	public void setRGB(int x, int y, int cx, int cy, int[] data, int offset, int w) {
		if (cm != null && data != null && data.length > 0) {
			for (int i = 0; i < data.length; i++)
				data[i] = cm.getRGB(data[i]);
		}
		bi.setRGB(x, y, cx, cy, data, offset, w);
	}

	@Override
	public int[] getRGB(int x, int y, int cx, int cy, int[] data, int offset, int width) {
		return bi.getRGB(x, y, cx, cy, data, offset, width);
	}

	@Override
	public int getRGB(int x, int y) {
		// if(x >= this.getWidth() || x < 0 || y >= this.getHeight() || y < 0)
		// return 0;
		if (cm == null)
			return bi.getRGB(x, y);
		else {
			int pix = bi.getRGB(x, y) & 0xFFFFFF;
			int[] vals = { (pix >> 16) & 0xFF, (pix >> 8) & 0xFF, (pix) & 0xFF };
			int out = cm.getDataElement(vals, 0);
			if (cm.getRGB(out) != bi.getRGB(x, y))
				LOG.warn("Did not get correct colour value for color (" + Integer.toHexString(pix) + "), got (" + cm.getRGB(out)
						+ ") instead");
			return out;
		}
	}

	@Override
	public void resizeDisplay(Dimension dimension) {
		BufferedImage bim = bi;
		bi = new BufferedImage(Math.max(dimension.width, 1), Math.max(dimension.height, 1), bi.getType());
		bi.getGraphics().drawImage(bim, 0, 0, null);
	}

	public String toString() {
		return getClass().getSimpleName();
	}

	@Override
	public PointerShape getPointerShape() {
		return pointer;
	}

	@Override
	public Point getPointerPosition() {
		return pointer.getBounds().getLocation();
	}

	@Override
	public void init() throws Exception {
	}

	@Override
	public void destroy() {
	}

	@Override
	public void keyEvent(RFBClient client, boolean down, int key) {
		char keychar = KeyEvent.CHAR_UNDEFINED;
		int keycode = KeyEvent.VK_UNDEFINED;
		int loc = KeyEvent.KEY_LOCATION_UNKNOWN;
		switch (key) {
		case RFBConstants.RFBKEY_SHIFT_LEFT:
			keycode = KeyEvent.VK_SHIFT;
			loc = KeyEvent.KEY_LOCATION_LEFT;
			break;
		case RFBConstants.RFBKEY_SHIFT_RIGHT:
			keycode = KeyEvent.VK_SHIFT;
			loc = KeyEvent.KEY_LOCATION_RIGHT;
			break;
		case RFBConstants.RFBKEY_CTRL_LEFT:
			keycode = KeyEvent.VK_CONTROL;
			loc = KeyEvent.KEY_LOCATION_LEFT;
			break;
		case RFBConstants.RFBKEY_CTRL_RIGHT:
			keycode = KeyEvent.VK_CONTROL;
			loc = KeyEvent.KEY_LOCATION_RIGHT;
			break;
		case RFBConstants.RFBKEY_META_LEFT:
			keycode = KeyEvent.VK_META;
			loc = KeyEvent.KEY_LOCATION_LEFT;
			break;
		case RFBConstants.RFBKEY_META_RIGHT:
			keycode = KeyEvent.VK_META;
			loc = KeyEvent.KEY_LOCATION_RIGHT;
			break;
		case RFBConstants.RFBKEY_ALT_LEFT:
			keycode = KeyEvent.VK_ALT;
			loc = KeyEvent.KEY_LOCATION_LEFT;
			break;
		case RFBConstants.RFBKEY_ALT_RIGHT:
			keycode = KeyEvent.VK_ALT_GRAPH;
			loc = KeyEvent.KEY_LOCATION_RIGHT;
			break;
		case RFBConstants.RFBKEY_F1:
		case RFBConstants.RFBKEY_F2:
		case RFBConstants.RFBKEY_F3:
		case RFBConstants.RFBKEY_F4:
		case RFBConstants.RFBKEY_F5:
		case RFBConstants.RFBKEY_F6:
		case RFBConstants.RFBKEY_F7:
		case RFBConstants.RFBKEY_F8:
		case RFBConstants.RFBKEY_F9:
		case RFBConstants.RFBKEY_F10:
		case RFBConstants.RFBKEY_F11:
		case RFBConstants.RFBKEY_F12:
			keycode = KeyEvent.VK_F1 + (key - RFBConstants.RFBKEY_F1);
			break;
		case RFBConstants.RFBKEY_BACKSPACE:
			keycode = KeyEvent.VK_BACK_SPACE;
			break;
		case RFBConstants.RFBKEY_TAB:
			keycode = KeyEvent.VK_TAB;
			break;
		case RFBConstants.RFBKEY_ENTER:
			keycode = KeyEvent.VK_ENTER;
			break;
		case RFBConstants.RFBKEY_ESCAPE:
			keycode = KeyEvent.VK_ESCAPE;
			break;
		case RFBConstants.RFBKEY_INSERT:
			keycode = KeyEvent.VK_INSERT;
			break;
		case RFBConstants.RFBKEY_DELETE:
			keycode = KeyEvent.VK_DELETE;
			break;
		case RFBConstants.RFBKEY_HOME:
			keycode = KeyEvent.VK_HOME;
			break;
		case RFBConstants.RFBKEY_END:
			keycode = KeyEvent.VK_END;
			break;
		case RFBConstants.RFBKEY_PGUP:
			keycode = KeyEvent.VK_PAGE_UP;
			break;
		case RFBConstants.RFBKEY_PGDN:
			keycode = KeyEvent.VK_PAGE_DOWN;
			break;
		case RFBConstants.RFBKEY_LEFT:
			keycode = KeyEvent.VK_LEFT;
			break;
		case RFBConstants.RFBKEY_RIGHT:
			keycode = KeyEvent.VK_RIGHT;
			break;
		case RFBConstants.RFBKEY_UP:
			keycode = KeyEvent.VK_UP;
			break;
		case RFBConstants.RFBKEY_DOWN:
			keycode = KeyEvent.VK_UP;
			break;
		default:
			keychar = (char) key;
			if ((keychar >= 'A' && keychar <= 'Z') || (keychar >= '0' && keychar <= '9'))
				keycode = keychar;
			else if (keychar >= 'a' && keychar <= 'z')
				keycode = keychar - 'a' + 'A';
			break;
		}
		switch (keycode) {
		case KeyEvent.VK_SHIFT:
			if (down)
				keyMods |= KeyEvent.SHIFT_DOWN_MASK;
			else
				keyMods &= ~(KeyEvent.SHIFT_DOWN_MASK);
			break;
		case KeyEvent.VK_CONTROL:
			if (down)
				keyMods |= KeyEvent.CTRL_DOWN_MASK;
			else
				keyMods &= ~(KeyEvent.CTRL_DOWN_MASK);
			break;
		case KeyEvent.VK_META:
			if (down)
				keyMods |= KeyEvent.META_DOWN_MASK;
			else
				keyMods &= ~(KeyEvent.META_DOWN_MASK);
			break;
		case KeyEvent.VK_ALT:
			if (down)
				keyMods |= KeyEvent.ALT_DOWN_MASK;
			else
				keyMods &= ~(KeyEvent.ALT_DOWN_MASK);
			break;
		case KeyEvent.VK_ALT_GRAPH:
			if (down)
				keyMods |= KeyEvent.ALT_GRAPH_DOWN_MASK;
			else
				keyMods &= ~(KeyEvent.ALT_GRAPH_DOWN_MASK);
			break;
		}
		EventListener[] l = uiListeners.getListeners(KeyListener.class);
		for (int i = l.length - 1; i >= 0; i--) {
			if (down) {
				((KeyListener) l[i]).keyPressed(new KeyEvent(fakeComponent, KeyEvent.KEY_PRESSED, System.currentTimeMillis(),
						keyMods, keycode, keychar, loc));
			} else {
				((KeyListener) l[i]).keyReleased(new KeyEvent(fakeComponent, KeyEvent.KEY_RELEASED, System.currentTimeMillis(),
						keyMods, keycode, keychar, loc));
			}
		}
		for (int i = l.length - 1; i >= 0; i--) {
			if (!down) {
				if (keychar != KeyEvent.CHAR_UNDEFINED)
					((KeyListener) l[i]).keyTyped(new KeyEvent(fakeComponent, KeyEvent.KEY_TYPED, System.currentTimeMillis(),
							keyMods, KeyEvent.VK_UNDEFINED, keychar, loc));
			}
		}
	}

	@Override
	public void mouseEvent(RFBClient client, int buttonMask, int x, int y) {
		if (x != pointer.getX() || y != pointer.getY()) {
			mouseMoved(x, y);
			pointer.setX(x);
			pointer.setY(y);
			EventListener[] l = uiListeners.getListeners(MouseMotionListener.class);
			for (int i = l.length - 1; i >= 0; i--) {
				((MouseMotionListener) l[i]).mouseMoved(
						new MouseEvent(fakeComponent, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), keyMods, x, y, 0, false));
			}
		}
		// TODO there is a still a bug in noVnc client. If you press button 1,
		// then button 3, then release
		// button 3, then release button 1, you never get the final release
		// event
		if (buttonMask != this.buttonMask) {
			EventListener[] l = uiListeners.getListeners(MouseListener.class);
			EventListener[] w = uiListeners.getListeners(MouseWheelListener.class);
			for (int i = 0; i < 8; i++) {
				int bitval = 1 << i;
				boolean is = (buttonMask & bitval) != 0;
				boolean was = (this.buttonMask & bitval) != 0;
				if (is != was) {
					if (i == 3 || i == 4) {
						if (is) {
							for (int j = w.length - 1; j >= 0; j--) {
								((MouseWheelListener) w[j]).mouseWheelMoved(new MouseWheelEvent(fakeComponent,
										MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), keyMods, x, y, 0, 0, 1, false,
										MouseWheelEvent.WHEEL_UNIT_SCROLL, i == 3 ? -1 : 1, i == 3 ? -1 : 1));
							}
						}
					} else {
						for (int j = l.length - 1; j >= 0; j--) {
							if (is) {
								((MouseListener) l[j]).mousePressed(new MouseEvent(fakeComponent, MouseEvent.MOUSE_PRESSED,
										System.currentTimeMillis(), keyMods, x, y, 0, i == 2, i + 1));
							} else {
								((MouseListener) l[j]).mouseReleased(new MouseEvent(fakeComponent, MouseEvent.MOUSE_RELEASED,
										System.currentTimeMillis(), keyMods, x, y, 0, i == 2, i + 1));
							}
						}
					}
				}
			}
			this.buttonMask = buttonMask;
		}
	}

	@Override
	public void setClipboardText(String string) {
		if (clipChannel != null) {
			clipboard.setContents(new StringSelection(string), null);
			clipChannel.focusGained(null);
		}
	}

	@Override
	public BufferedImage grabArea(Rectangle area) {
		return bi.getSubimage(area.x, area.y, area.width, area.height);
	}

	@Override
	public void init(RdesktopCanvas canvas) {
		this.canvas = canvas;
	}

	@Override
	public void repaint(int x, int y, int cx, int cy) {
		if (x < 0)
			x = 0;
		if (y < 0)
			y = 0;
		if (cy < 1)
			cy = 1;
		if (cx < 1)
			cx = 1;
		fireDamageEvent("Repaint", new Rectangle(x, y, cx, cy), -1);
	}

	@Override
	public RdpCursor createCursor(String name, Point hotspot, Image data) {
		return new RdpCursor(hotspot, name, data);
	}

	//
	@Override
	public void setCursor(RdpCursor cursor) {
		if (cursor == null) {
			clearCursor();
		} else {
			pointer.setHotX(cursor.getHotspot().x);
			pointer.setHotY(cursor.getHotspot().y);
			pointer.setHeight(cursor.getData().getHeight(null));
			pointer.setWidth(cursor.getData().getWidth(null));
			pointer.setData((BufferedImage) cursor.getData());
			firePointerChange(getPointerShape());
		}
	}

	private void clearCursor() {
		pointer.setHotX(0);
		pointer.setHotY(0);
		pointer.setData(new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB));
		pointer.setHeight(16);
		pointer.setWidth(16);
	}

	@Override
	public Rectangle getBounds() {
		Point p = getLocationOnScreen();
		return new Rectangle(p.x, p.y, getDisplayWidth(), getDisplayHeight());
	}

	@Override
	public Point getLocationOnScreen() {
		return new Point(0, 0);
	}

	@Override
	public void addMouseListener(MouseListener mouseListener) {
		uiListeners.add(MouseListener.class, mouseListener);
	}

	@Override
	public void removeMouseListener(MouseListener mouseListener) {
		uiListeners.remove(MouseListener.class, mouseListener);
	}

	@Override
	public void addMouseMotionListener(MouseMotionListener mouseMotionListener) {
		uiListeners.add(MouseMotionListener.class, mouseMotionListener);
	}

	@Override
	public void removeMouseMotionListener(MouseMotionListener mouseMotionListener) {
		uiListeners.remove(MouseMotionListener.class, mouseMotionListener);
	}

	@Override
	public void addMouseWheelListener(MouseWheelListener mouseWheelListener) {
		uiListeners.add(MouseWheelListener.class, mouseWheelListener);
	}

	@Override
	public void removeMouseWheelListener(MouseWheelListener mouseWheelListener) {
		uiListeners.remove(MouseWheelListener.class, mouseWheelListener);
	}

	@Override
	public void addKeyListener(KeyListener keyListener) {
		uiListeners.add(KeyListener.class, keyListener);
	}

	@Override
	public void removeKeyListener(KeyListener keyListener) {
		uiListeners.remove(KeyListener.class, keyListener);
	}

	@Override
	public void repaint() {
		fireDamageEvent("FullRepaint", new Rectangle(0, 0, getDisplayWidth(), getDisplayHeight()), -1);
	}

	@Override
	public int getWidth() {
		return getDisplayWidth();
	}

	@Override
	public int getHeight() {
		return getDisplayHeight();
	}

	@Override
	public boolean resize(ScreenData screen) {
		if (displayControlChannel != null && displayControlChannel.isInitialised()) {
			List<ScreenDetail> l = screen.getAllDetails();
			List<MonitorLayout> m = new ArrayList<MonitorLayout>(l.size());
			int n = 0;
			for (ScreenDetail d : l) {
				m.add(new MonitorLayout(n == 0, d.getX(), d.getY(), d.getWidth(), d.getHeight()));
				n++;
			}
			try {
				LOG.info(String.format("Sending new size data to to RDP server %dx%d", screen.getWidth(), screen.getHeight()));
				displayControlChannel.sendDisplayControlCaps(m);
				canvas.backingStoreResize(bi.getWidth(), bi.getHeight(), true);
				return true;
			} catch (Exception e) {
				// TODO should disconnect?
				LOG.error("Failed to set remote desktop size.", e);
			}
		}
		return false;
	}

	public void serverResize(int width, int height, boolean clientInitiated) {
		fireScreenBoundsChanged(new ScreenData(new ScreenDimension(width, height)), clientInitiated);
	}
}
