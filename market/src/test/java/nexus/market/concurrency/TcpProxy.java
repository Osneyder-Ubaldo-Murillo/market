package nexus.market.concurrency;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Proxy TCP mínimo para pruebas: reenvía a un MySQL real y permite SIMULAR su caída y su recuperación sin tocar el
 * contenedor. {@link #stop()} cierra el puerto (los intentos nuevos reciben "Connection refused", como con MySQL
 * detenido) y corta las conexiones abiertas; {@link #start()} lo reabre en el MISMO puerto.
 */
final class TcpProxy implements AutoCloseable {

    private final String targetHost;
    private final int targetPort;
    private final int port;
    private final Set<Socket> open = ConcurrentHashMap.newKeySet();
    private ServerSocket server;

    TcpProxy(String targetHost, int targetPort) throws IOException {
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        try (ServerSocket probe = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())) {
            this.port = probe.getLocalPort();
        }
        start();
    }

    int port() {
        return port;
    }

    synchronized void start() throws IOException {
        if (server != null && !server.isClosed()) {
            return;
        }
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port), 200);
        server = socket;
        Thread acceptor = new Thread(() -> acceptLoop(socket), "tcp-proxy-acceptor-" + port);
        acceptor.setDaemon(true);
        acceptor.start();
    }

    synchronized void stop() {
        closeQuietly(server);
        for (Socket socket : open) {
            closeQuietly(socket);
        }
        open.clear();
    }

    private void acceptLoop(ServerSocket socket) {
        while (!socket.isClosed()) {
            try {
                Socket client = socket.accept();
                Socket upstream = new Socket(targetHost, targetPort);
                open.add(client);
                open.add(upstream);
                pipe(client, upstream);
                pipe(upstream, client);
            } catch (IOException ex) {
                // servidor cerrado (stop) o destino inalcanzable: se sigue o se termina según el estado
            }
        }
    }

    private void pipe(Socket from, Socket to) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[8192];
            try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                    out.flush();
                }
            } catch (IOException ex) {
                // conexión cortada
            } finally {
                closeQuietly(from);
                closeQuietly(to);
                open.remove(from);
                open.remove(to);
            }
        }, "tcp-proxy-pipe");
        thread.setDaemon(true);
        thread.start();
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // nada que hacer
        }
    }

    @Override
    public void close() {
        stop();
    }
}
