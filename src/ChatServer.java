import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.util.*;

class User {
  String name;
  SocketChannel sc;
  State state;
  String room;

  User(String name, SocketChannel sc) {
    this.name = name;
    this.sc = sc;
    this.state = State.INIT;
    this.room = null;
  }
}

class Room {
  String name;
  List<String> RoomUsers;

  Room(String name) {
    this.name = name;
    this.RoomUsers = new ArrayList<>();
  }

  final void updateRoom(String u) {
    this.RoomUsers.add(u);
  }

  final void removeFromRoom(String u) {
    this.RoomUsers.remove(u);
  }
}

enum State {
  INIT,
  OUTSIDE,
  INSIDE;
}

public class ChatServer {
  // Map of users and rooms
  static private final Map<String, User> UsersMap = new HashMap<>();
  static private final Map<String, Room> RoomsMap = new HashMap<>();

  // A pre-allocated buffer for the received data
  static private final ByteBuffer buffer = ByteBuffer.allocate(16384);

  // Decoder for incoming text -- assume UTF-8
  static private final Charset charset = Charset.forName("UTF8");
  static private final CharsetDecoder decoder = charset.newDecoder();

  static public void main(String args[]) throws Exception {
    // Parse port from command line
    int port = Integer.parseInt(args[0]);

    try {
      // Instead of creating a ServerSocket, create a ServerSocketChannel
      ServerSocketChannel ssc = ServerSocketChannel.open();

      // Set it to non-blocking, so we can use select
      ssc.configureBlocking(false);

      // Get the Socket connected to this channel, and bind it to the
      // listening port
      ServerSocket ss = ssc.socket();
      InetSocketAddress isa = new InetSocketAddress(port);
      ss.bind(isa);

      // Create a new Selector for selecting
      Selector selector = Selector.open();

      // Register the ServerSocketChannel, so we can listen for incoming
      // connections
      ssc.register(selector, SelectionKey.OP_ACCEPT);
      System.out.println("Listening on port " + port);

      while (true) {
        // See if we've had any activity -- either an incoming connection,
        // or incoming data on an existing connection
        int num = selector.select();

        // If we don't have any activity, loop around and wait again
        if (num == 0) {
          continue;
        }

        // Get the keys corresponding to the activity that has been
        // detected, and process them one by one
        Set<SelectionKey> keys = selector.selectedKeys();
        Iterator<SelectionKey> it = keys.iterator();
        while (it.hasNext()) {
          // Get a key representing one of bits of I/O activity
          SelectionKey key = it.next();

          // What kind of activity is it?
          if (key.isAcceptable()) {

            // It's an incoming connection. Register this socket with
            // the Selector so we can listen for input on it
            Socket s = ss.accept();
            System.out.println("Got connection from " + s);

            // Make sure to make it non-blocking, so we can use a selector
            // on it.
            SocketChannel sc = s.getChannel();
            sc.configureBlocking(false);

            // Register it with the selector, for reading
            sc.register(selector, SelectionKey.OP_READ, new User("", sc));

          } else if (key.isReadable()) {

            SocketChannel sc = null;

            try {

              // It's incoming data on a connection -- process it
              sc = (SocketChannel) key.channel();
              boolean ok = processInput(sc, selector, key);

              // If the connection is dead, remove it from the selector
              // and close it
              if (!ok) {
                key.cancel();
                Socket s = null;
                processBye(sc, key, true);
                closeConnection(s, sc);
              }

            } catch (IOException ie) {

              // On exception, remove this channel from the selector
              key.cancel();

              try {
                processBye(sc, key, true);
                sc.close();
              } catch (IOException ie2) {
                System.out.println(ie2);
              }

              System.out.println("Closed " + sc);
            }
          }
        }

        // We remove the selected keys, because we've dealt with them.
        keys.clear();
      }
    } catch (IOException ie) {
      System.err.println(ie);
    }
  }

  static private void closeConnection(Socket s, SocketChannel sc) {
    try {
      s = sc.socket();
      System.out.println("Closing connection to " + s);
      s.close();
    } catch (IOException ie) {
      System.err.println("Error closing socket " + s + ": " + ie);
    }
  }

  // Just read the message from the socket and send it to stdout
  static private boolean processInput(SocketChannel sc, Selector selector, SelectionKey keySource) throws IOException {
    // Read the message to the buffer
    buffer.clear();
    sc.read(buffer);
    buffer.flip();    

    // If no data, close the connection
    if (buffer.limit() == 0) {
      return false;
    }

    // Decode and print the message to stdout
    String message = decoder.decode(buffer).toString();
    processMessage(sc, keySource, message);
    return true;
  }

  // Process the message received from the socket
  static private void processMessage(SocketChannel sc, SelectionKey keySource, String message) throws IOException {
    // The message is a command
    if (message.charAt(0) == '/' && message.charAt(1) != '/') {
      if (message.contains(" ")) {
        String command[] = message.split(" ", 2);
        String nick = command[1].replaceAll("[\\n\\t ]", "");
        nick = nick.substring(0, nick.length() - 1);
        if (nick.length() == 0) {
          buffer.clear();
          buffer.put("ERROR\n".getBytes(charset));
          buffer.flip();
          sc.write(buffer);
          return;
        }
        switch (command[0]) {
          case "/nick":
            processNick(sc, keySource, nick);
            break;
          case "/join":
            processJoin(sc, keySource, nick);
            break;
          case "/priv":
            String priv[] = message.split(" ", 3);
            if(priv.length < 3){
              buffer.clear();
              buffer.put("ERROR\n".getBytes(charset));
              buffer.flip();
              sc.write(buffer);
              return;
            }
            String msg = priv[2].replaceAll("[\\n\\t]", ""); 
            processPriv(sc, keySource, priv[1], msg);
            break;
        }
      } else {
        switch (message.substring(0, message.length() - 2)) {
          case "/leave":
            processLeave(sc, keySource, false);
            break;
          case "/bye":
            processBye(sc, keySource, false);
            break;
        }
      }
    }
    // The message is not a command
    else {
      if (message.charAt(0) == '/')
        message = message.substring(1, message.length());
      message(sc, keySource, message);
    }
  }

  static private void processNick(SocketChannel sc, SelectionKey keySource, String newName) throws IOException {
    User actual = (User) keySource.attachment();
    if (UsersMap.containsKey(newName)) {
      {
        buffer.clear();
        buffer.put("ERROR\n".getBytes(charset));
        buffer.flip();
        sc.write(buffer);
        return;
      }
    }
    UsersMap.remove(actual.name);
    String oldNick = actual.name;
    actual.name = newName;
    if (actual.state == State.INSIDE) {
      Room updateRoom = RoomsMap.get(actual.room);
      updateRoom.updateRoom(actual.name);
      updateRoom.removeFromRoom(oldNick);
      RoomsMap.put(actual.room, updateRoom);
      for (String user : RoomsMap.get(actual.room).RoomUsers) {
        if (actual.name == user)
          continue;
        User cur = UsersMap.get(user);
        buffer.clear();
        buffer.put(("NEWNICK " + oldNick + " " + actual.name + "\n").getBytes(charset));
        buffer.flip();
        cur.sc.write(buffer);
      }
    }
    if (actual.state == State.INIT)
      actual.state = State.OUTSIDE;
    UsersMap.put(actual.name, actual);
    buffer.clear();
    buffer.put("OK\n".getBytes(charset));
    buffer.flip();
    sc.write(buffer);
  }

  static private void processJoin(SocketChannel sc, SelectionKey keySource, String roomName) throws IOException {

    User joining = (User) keySource.attachment();

    if (joining.state == State.INIT) {
      buffer.clear();
      buffer.put("ERROR\n".getBytes(charset));
      buffer.flip();
      sc.write(buffer);
      return;
    }
    if ((!roomName.equals(joining.room)) == (joining.state == State.INSIDE)) {
      processLeave(sc, keySource, true);
      processJoin(sc, keySource, roomName);
      return;
    }
    if (!RoomsMap.keySet().contains(roomName)) {
      Room newRoom = new Room(roomName);

      newRoom.updateRoom(joining.name);
      RoomsMap.put(roomName, newRoom);
    } else {
      Room joiningRoom = RoomsMap.get(roomName);
      if (joiningRoom.RoomUsers.contains(joining.name)) {
        buffer.clear();
        buffer.put("ERROR\n".getBytes(charset));
        buffer.flip();
        sc.write(buffer);
        return;
      }
      joiningRoom.updateRoom(joining.name);
      RoomsMap.put(roomName, joiningRoom);
    }
    joining.room = roomName;
    joining.state = State.INSIDE;
    UsersMap.put(joining.name, joining);
    buffer.clear();
    buffer.put("OK\n".getBytes(charset));
    buffer.flip();
    sc.write(buffer);
    for (String user : RoomsMap.get(roomName).RoomUsers) {
      if (user != joining.name) {
        buffer.clear();
        buffer.put(("JOINED " + joining.name + "\n").getBytes(charset));
        buffer.flip();
        UsersMap.get(user).sc.write(buffer);
      }
    }
  }

  static private void processLeave(SocketChannel sc, SelectionKey keySource, Boolean joiningOther) throws IOException {
    User leaving = (User) keySource.attachment();
    if (leaving.state != State.INSIDE) {
      buffer.clear();
      buffer.put("ERROR\n".getBytes(charset));
      buffer.flip();
      sc.write(buffer);
      return;
    }
    Room leavingRoom = RoomsMap.get(leaving.room);
    leavingRoom.removeFromRoom(leaving.name);
    RoomsMap.put(leaving.room, leavingRoom);
    for (String user : RoomsMap.get(leaving.room).RoomUsers) {
      if (leaving.name != user) {
        buffer.clear();
        buffer.put(("LEFT " + leaving.name + "\n").getBytes(charset));
        buffer.flip();
        UsersMap.get(user).sc.write(buffer);
      }
    }
    leaving.room = null;
    leaving.state = State.OUTSIDE;
    UsersMap.put(leaving.name, leaving);
    if (!joiningOther) {
      buffer.clear();
      buffer.put("OK\n".getBytes(charset));
      buffer.flip();
      sc.write(buffer);
    }
  }

  static private void processBye(SocketChannel sc, SelectionKey keySource, Boolean dc) throws IOException {
    User u = (User) keySource.attachment();
    if (u.state == State.INSIDE)
      processLeave(sc, keySource, true);
    UsersMap.remove(u.name);
    if (!dc) {
      buffer.clear();
      buffer.put("BYE\n".getBytes(charset));
      buffer.flip();
      sc.write(buffer);
    }
    closeConnection(null, sc);
  }

  static private void message(SocketChannel sc, SelectionKey keySource, String message) throws IOException {
    User sender = (User) keySource.attachment();
    if (sender.state != State.INSIDE) {
      buffer.clear();
      buffer.put("ERROR\n".getBytes(charset));
      buffer.flip();
      sc.write(buffer);
      return;
    }
    // System.out.println(sender.name + " " + sender.room + " " + sender.state);
    for (String user : RoomsMap.get(sender.room).RoomUsers) {
      if (sender.name == user)
        continue;
      User cur = UsersMap.get(user);
      buffer.clear();
      buffer.put(("MESSAGE " + sender.name + " " + message).getBytes(charset));
      buffer.flip();
      cur.sc.write(buffer);
    }
  }

  static private void processPriv(SocketChannel sc, SelectionKey keySource, String dest, String message) throws IOException {
    User sender = (User) keySource.attachment();
    if (sender.state != State.INIT) {
      if (!UsersMap.keySet().contains(dest)) {
        buffer.clear();
        buffer.put("ERROR\n".getBytes(charset));
        buffer.flip();
        sc.write(buffer);
        return;
      }
      buffer.clear();
      buffer.put("OK\n".getBytes(charset));
      buffer.flip();
      sc.write(buffer);
      User recUser = UsersMap.get(dest);
      buffer.clear();
      buffer.put(("PRIVATE " + sender.name + " " + message + "\n").getBytes(charset));
      buffer.flip();
      recUser.sc.write(buffer);
    }
  }
}
