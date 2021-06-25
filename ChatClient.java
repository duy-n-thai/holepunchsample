/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.*;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;

/**
 *
 * @author user
 */
public class ChatClient {

    private static String _uwid = "";
    private static String _w_name = "";
    private static InetAddress _server_ip;
    private static int _server_udp_port;
    private final DatagramSocket _dg_socket;

    private final Object _syn = new Object();
    private final Object _syn_socket = new Object();

    private ArrayList<PeerClient> _table = new ArrayList<>();

    private ArrayBlockingQueue<DataPackage> _server_incoming_queue = new ArrayBlockingQueue<>(100);

    private ArrayBlockingQueue<DataPackage> _peer_incoming_queue = new ArrayBlockingQueue<>(100);

    public ChatClient() throws IOException {

        //Create a Datagram Socket for UDP messages
        _dg_socket = new DatagramSocket();

    }

    public void run() {

        //
        new Thread(() -> {
            while(true) {
                try {
                    DataPackage p = _server_incoming_queue.take();
                    switch (p._cmd) {
                        case "ack":
                            System.out.println(p._data);
                            break;
                        case "table_changed":
                            String cmd = String.format("get_table;%s;%s", _uwid, _w_name);
                            send_command(cmd);
                            break;
                        case "table":
                            update_table(p);
                            break;
                        default:
                            System.err.println("Invalid command:" + p._cmd);
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }).start();

        //
        new Thread(() -> {
            while(true) {
                try {
                    DataPackage p = _peer_incoming_queue.take();
                    System.out.println("Receive from peer:" + p._data);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }).start();

        //
        new Thread(() -> {
            try {
                while(true) {
                    int length = 1024;
                    byte[] buff = new byte[length];
                    DatagramPacket receive_packet = new DatagramPacket(buff, length);
                    _dg_socket.receive(receive_packet);
                    if (receive_packet.getLength() < 1) {
                        continue;
                    }
                    String str = new String(receive_packet.getData());   //get data from the received udp packet
                    //str = str.trim();
                    //System.out.println("A:" + str);
                    String[] parts = str.split(":");
                    String ip = receive_packet.getAddress().getHostAddress().trim();
                    int port = receive_packet.getPort();
                    DataPackage pack = new DataPackage(parts[0], parts[1], parts[2], ip, port);
                    if (parts[0].equals("server")) {
                        _server_incoming_queue.put(pack);
                    } else {
                        _peer_incoming_queue.put(pack);
                    }
                }
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        }).start();

        //
        new Thread(() -> {
            while(true) {
                send_message();
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ignored) {}
            }
        }).start();

        //
        new Thread(() -> {
            if (!register()) {
                System.err.println("Can't register");
                System.exit(0);
            }
        }).start();

    }

    private boolean register() {
        try {
            String cmd = String.format("register;%s;%s", _uwid, _w_name);
            byte[] buff = cmd.getBytes();
            DatagramPacket sp = new DatagramPacket(buff, buff.length, _server_ip, _server_udp_port);
            synchronized (_syn_socket) {
                _dg_socket.send(sp);
            }
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    private boolean drop_out() {
        try {
            String cmd = String.format("drop_out;%s;%s", _uwid);
            byte[] buff = cmd.getBytes();
            DatagramPacket sp = new DatagramPacket(buff, buff.length, _server_ip, _server_udp_port);
            synchronized (_syn_socket) {
                _dg_socket.send(sp);
            }
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    private void update_table(DataPackage p) {
        synchronized (_syn) {
            _table.clear();
            //
            System.out.println("Update table:" + p._data);
            String[] parts = p._data.split(";");
            for (int i = 0; i < parts.length / 4; i++) {
                String uwid = parts[i * 4];
                String w_name = parts[i * 4 + 1];
                String ip = parts[i * 4 + 2];
                int port = Integer.parseInt(parts[i * 4 + 3].trim(), 10);
                PeerClient peer = new PeerClient(uwid, w_name, ip, port);
                _table.add(peer);
            }
        }
    }

    private boolean send_message() {
        synchronized (_syn) {
            for (int i = 0; i < _table.size(); i++) {
                PeerClient peer = _table.get(i);
                String msg = String.format("peer:msg:%s-%s-%d", _uwid, _w_name, i);
                // String msg = "peer:msg:test";
                byte[] buff = msg.getBytes();
                try {
                    DatagramPacket sp = new DatagramPacket(buff, buff.length, InetAddress.getByName(peer._ip), peer._port);
                    synchronized (_syn_socket) {
                        _dg_socket.send(sp);
                    }
                } catch (IOException ignored) {

                }
            }
        }
        return true;
    }

    private boolean send_command(String cmd) {
        try {
            byte[] buff = cmd.getBytes();
            DatagramPacket sp = new DatagramPacket(buff, buff.length, _server_ip, _server_udp_port);
            synchronized (_syn_socket) {
                _dg_socket.send(sp);
            }
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static class PeerClient {
        public String _uwid;
        public String _w_name;
        public String _ip;
        public int _port;
        public PeerClient(String uwid, String w_name, String ip, int port) {
            _uwid = uwid;
            _w_name = w_name;
            _ip = ip;
            _port = port;
        }
    }

    public static class DataPackage {
        public String _type = "";
        public String _cmd = "";
        public String _data = "";
        public String _peer_ip = "";
        public int _peer_port = 0;

        public DataPackage(String type, String cmd, String data, String peer_ip, int peer_port) {
            _type = type;
            _cmd = cmd;
            _data = data;
            _peer_ip = peer_ip;
            _peer_port = peer_port;
        }
    }

    public static void main(String[] args) throws UnknownHostException, IOException {

        _server_ip = InetAddress.getByName("192.168.2.9");
        _server_udp_port = 35906;
        if (args.length == 2) {
            try {
                _uwid = args[0].trim();
                _w_name = args[1].trim();
                new ChatClient().run();
            } catch (Exception ex) {
                System.err.println("Error in input");
                System.out.println("USAGE: java ClientA serverIp serverTcpPort serverUdpPort");
                System.out.println("Example: java ClientA 127.0.0.1 9000 9001");
                System.exit(0);
            }

        } else {
            System.out.println("You need to provide uwid and w_name!");
            System.exit(0);
        }
    }
}
