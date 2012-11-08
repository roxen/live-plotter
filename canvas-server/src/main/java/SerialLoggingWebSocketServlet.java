import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;

import org.apache.log4j.Logger;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.websocket.WebSocket;
import org.eclipse.jetty.websocket.WebSocket.OnTextMessage;
import org.eclipse.jetty.websocket.WebSocketServlet;

public class SerialLoggingWebSocketServlet extends WebSocketServlet {

    // Supported operations
    private final int MOVE_TO = 0, LINE_TO = 1, BEGIN_AT = 2, CLEAR = 3, DONE = 4;

    // The height of the html canvas
    private static final int CANVAS_HEIGHT = 400;

    // Enable or disable serial logging to plotter
    private static final boolean ENABLE_SERIAL = false;
    private SerialCommunicator serial;

    // One pixel on canvas maps to 2mm for plotter
    // (because the plotter can handle approx 20x20cm and we're using
    // a 400x400px canvas)
    private static final int PIXEL_TO_MM_RATIO = 2;

    private static final long serialVersionUID = 1L;
    private static final String CLASS_NAME = SerialLoggingWebSocketServlet.class.getName();
    private static final Logger LOG = Logger.getLogger(CLASS_NAME);

    private final Set<SerialLoggingWebSocket> members = new HashSet<SerialLoggingWebSocket>();
    private final List<String> history = new ArrayList<String>();
    private volatile SerialLoggingWebSocket currentPrintingSocket = null;

    @Override
    public void init() throws ServletException {
        super.init();

        if (ENABLE_SERIAL) {
            serial = new SerialCommunicator();
            serial.initialize();
        }
    }

    public WebSocket doWebSocketConnect(HttpServletRequest request, String protocol) {
        LOG.debug("doWebSocketConnect");
        return new SerialLoggingWebSocket();
    }

    public class SerialLoggingWebSocket implements WebSocket, OnTextMessage {

        Connection connection;

        public void onClose(int closeCode, String message) {
            LOG.debug("onClose");
            synchronized (members) {
                members.remove(this);
            }
        }

        public void onOpen(Connection connection) {
            LOG.debug("onOpen");
            this.connection = connection;

            // Replay history for newcomer
            List<String> historyCopy = null;
            synchronized (history) {
                historyCopy = new ArrayList<String>(history.size());
                for (String historyEntry : history) {
                    historyCopy.add(historyEntry);
                }
            }
            try {
                for (String message : historyCopy) {
                    connection.sendMessage(message);
                }
            } catch (IOException e) {
                LOG.warn("Error sending message", e);
            }

            synchronized (members) {
                members.add(this);
            }
        }

        @SuppressWarnings("unchecked")
        public void onMessage(String message) {
            LOG.trace(message);

            Map<String, Object> messageMap = (Map<String, Object>) JSON.parse(message);
            Map<String, Number> data = (Map<String, Number>) messageMap.get("data");
            int type = ((Number) messageMap.get("type")).intValue();

            // Noone cares about moveTos except for plotter
            if (!ENABLE_SERIAL && type == MOVE_TO) {
                return;
            }

            try {
                // Someone's printing and it is not us
                if (currentPrintingSocket != null && currentPrintingSocket != SerialLoggingWebSocket.this) {
                    return;
                } else {
                    synchronized (this) {
                        if (currentPrintingSocket != null && currentPrintingSocket != SerialLoggingWebSocket.this) {
                            return;
                        } else if (currentPrintingSocket == null) {
                            // We do not want to start drawing from someone
                            // else's latest point (that's going to create a
                            // weird line)
                            if (type == LINE_TO) {
                                return;
                            }

                            currentPrintingSocket = SerialLoggingWebSocket.this;
                        }

                        if (type == CLEAR) {
                            synchronized (history) {
                                history.clear();
                            }
                        } else if (type == BEGIN_AT || type == LINE_TO || type == DONE) {
                            synchronized (history) {
                                history.add(message);
                            }
                        }

                        // Send event to all hooked up browsers
                        for (SerialLoggingWebSocket member : members) {
                            try {
                                member.connection.sendMessage(message);
                            } catch (IOException e) {
                                LOG.warn("Error sending message", e);
                            }
                        }

                        // Send event to plotter if enabled
                        if (ENABLE_SERIAL) {
                            // Plotter supports move to, line to and begin at
                            if (type == MOVE_TO || type == LINE_TO || type == BEGIN_AT) {
                                byte typeByte = 0; // move to in plotter program
                                if (type == LINE_TO) {
                                    typeByte = 1; // line to in plotter program
                                }

                                int x = data.get("x").intValue() / PIXEL_TO_MM_RATIO;
                                int y = (CANVAS_HEIGHT - data.get("y").intValue()) / PIXEL_TO_MM_RATIO;

                                try {
                                    serial.output.write(new byte[] { (byte) typeByte, (byte) x, (byte) y });
                                } catch (IOException e) {
                                    LOG.warn("Error writing to serial", e);
                                }
                            }
                        }

                        if (type == CLEAR || type == DONE) {
                            // Done, release lock
                            currentPrintingSocket = null;
                        }
                    }
                }
            } catch (Exception e) {
                LOG.warn("General unexpected error", e);
            }
        }
    }
}
