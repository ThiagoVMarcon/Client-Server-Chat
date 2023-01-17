import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.net.*;
import java.util.*;
import java.awt.*;
import java.awt.List;
import java.awt.event.*;
import javax.swing.*;

public class ChatClient {

    // Variáveis relacionadas com a interface gráfica --- * NÃO MODIFICAR *
    JFrame frame = new JFrame("Chat Client");
    private JTextField chatBox = new JTextField();
    private JTextArea chatArea = new JTextArea();
    // --- Fim das variáveis relacionadas coma interface gráfica

    // Se for necessário adicionar variáveis ao objecto ChatClient, devem
    // ser colocadas aqui

    // A pre-allocated buffer for the received data
    static private final ByteBuffer buffer = ByteBuffer.allocate(16384);
    SocketChannel sc = null;
    java.util.List commands = Arrays.asList("/nick", "/join", "/leave", "/bye", "/priv");

    // Decoder for incoming text -- assume UTF-8
    static private final Charset charset = Charset.forName("UTF8");
    static private final CharsetDecoder decoder = charset.newDecoder();
    
    // Método a usar para acrescentar uma string à caixa de texto
    // * NÃO MODIFICAR *
    public void printMessage(final String message) {
        chatArea.append(message);
    }

    // Construtor
    public ChatClient(String server, int port) throws IOException {

        // Inicialização da interface gráfica --- * NÃO MODIFICAR *
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        JPanel panel = new JPanel();
        panel.setLayout(new BorderLayout());
        panel.add(chatBox);
        frame.setLayout(new BorderLayout());
        frame.add(panel, BorderLayout.SOUTH);
        frame.add(new JScrollPane(chatArea), BorderLayout.CENTER);
        frame.setSize(500, 300);
        frame.setVisible(true);
        chatArea.setEditable(false);
        chatBox.setEditable(true);
        chatBox.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                try {
                    newMessage(chatBox.getText());
                } catch (IOException ex) {
                } finally {
                    chatBox.setText("");
                }
            }
        });
        frame.addWindowListener(new WindowAdapter() {
            public void windowOpened(WindowEvent e) {
                chatBox.requestFocusInWindow();
            }
        });
        // --- Fim da inicialização da interface gráfica

        // Se for necessário adicionar código de inicialização ao
        // construtor, deve ser colocado aqui

        InetSocketAddress isa = new InetSocketAddress(server, port);
        sc = SocketChannel.open(isa);
    }

    // Método invocado sempre que o utilizador insere uma mensagem
    // na caixa de entrada
    public void newMessage(String message) throws IOException {
        // PREENCHER AQUI com código que envia a mensagem ao servidor
        // tratar comandos
		// if (message.charAt(0) == '/' && message.charAt(1) != '/') {
		// 	String notCommand[] = message.split(" ", 2);
        //     if (!commands.contains(notCommand[0])) {
		// 		message = "/" + message;
		// 	}
        buffer.clear();
        sc.write(charset.encode(message + '\n'));
        //}
    }

    private String friendlierFormat(String message) throws IOException {
        // if (message.charAt(message.length() - 1) == '\n') {
		// 	message = message.substring(0, message.length() - 1);
		// }
        String ffmessage[] = message.split(" ", 3);  
        switch (ffmessage[0]) {
          case "MESSAGE":
            message = ffmessage[1] + ": " + ffmessage[2] + '\n';
            break;
          case "JOINED":
            message = ffmessage[1] + " entrou na sala";
            break;
          case "NEWNICK":
            message = ffmessage[1] + " mudou de nome para" + ffmessage[2] + '\n';
            break;
          case "LEFT":
            message = ffmessage[1] + " saiu da sala";
            break;
          case "BYE":
            message = "até mais!" + '\n';
            break;
          case "PRIV":
            message = ffmessage[1] + " (mensagem privada): " + ffmessage[2] + '\n';
            break;
        }
        return message;
    }
    
    // Método principal do objecto
    public void run() throws IOException {
        while(true) {
            try {
                buffer.clear();
                sc.read(buffer);
                buffer.flip();
                printMessage(friendlierFormat(decoder.decode(buffer).toString()));
            }
            catch (IOException e) {
                System.out.println("Client Error: " + e);
            }
        }
    }
    
    // Instancia o ChatClient e arranca-o invocando o seu método run()
    // * NÃO MODIFICAR *
    public static void main(String[] args) throws IOException {
        ChatClient client = new ChatClient(args[0], Integer.parseInt(args[1]));
        client.run();
    }

}
