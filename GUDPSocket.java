import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;


public class GUDPSocket implements GUDPSocketAPI {
    DatagramSocket datagramSocket;
    public boolean sendstart = false;
    public boolean finishsend = false;
    public boolean receiveack = false;
    public boolean receivestart = false;

    ReceiveThread2 receiveThread2;
    public PriorityQueue<GUDPPacket> priorityQueue;
    public LinkedBlockingQueue<GUDPPacket> receivequene;

    int acksign = 0;
    int sent = 0;

    int sendacksign = 0;
    public int rand;
    SendThread sendThread;
    ReceiveThread receiveThread;

    public LinkedList<GUDPbuffer> sendbuffer;
    public LinkedList<GUDPbuffer> ackbuffer;
    public GUDPSocket(DatagramSocket socket) {
        datagramSocket = socket;
    }

    public void send(DatagramPacket packet) throws IOException {
//        GUDPPacket gudppacket = GUDPPacket.encapsulate(packet);
//        DatagramPacket udppacket = gudppacket.pack();
//        datagramSocket.send(udppacket);
        if(!sendstart){
            InetSocketAddress addressBSN = new InetSocketAddress(packet.getAddress(),packet.getPort());
            ByteBuffer BSN_data = ByteBuffer.allocate(GUDPPacket.HEADER_SIZE);
            BSN_data.order(ByteOrder.BIG_ENDIAN);
            GUDPPacket gudpBSN = new GUDPPacket(BSN_data);

            //configuration
            gudpBSN.setType(GUDPPacket.TYPE_BSN);
            gudpBSN.setVersion(GUDPPacket.GUDP_VERSION);
            gudpBSN.setPayloadLength(0);
            gudpBSN.setSocketAddress(addressBSN);
            Random rand = new Random();
            int BSNseq = rand.nextInt();
            this.rand = BSNseq;
            gudpBSN.setSeqno(this.rand);
            sendbuffer = new LinkedList<>();
            ackbuffer = new LinkedList<>();
            sendbuffer.add(new GUDPbuffer(gudpBSN));
            sendstart = true;

        }

            this.rand++;
            InetSocketAddress addressdata = new InetSocketAddress(packet.getAddress(),packet.getPort());
            GUDPPacket gudpDATA = GUDPPacket.encapsulate(packet);
            gudpDATA.setVersion(GUDPPacket.GUDP_VERSION);
            gudpDATA.setType(GUDPPacket.TYPE_DATA);
            gudpDATA.setPayloadLength(packet.getLength());
            gudpDATA.setSeqno(this.rand);
            gudpDATA.setSocketAddress(addressdata);
            sendbuffer.add(new GUDPbuffer(gudpDATA));



    }

    public void receive(DatagramPacket packet) throws IOException {
        if(!receivestart){
            receivequene = new LinkedBlockingQueue<>();
            priorityQueue = new PriorityQueue<>(Comparator.comparingInt(GUDPPacket::getSeqno));
            receiveThread2 = new ReceiveThread2();
            receiveThread2.start();
            receivestart = true;
        }
        try{
            GUDPPacket gudpPacket = receivequene.take();
            if ( gudpPacket!=null ) {
                gudpPacket.decapsulate(packet);
            }
        }
        catch (InterruptedException e)
        {
            throw new IOException(e);
        }


    }

    public void finish() throws IOException {

        //thread
        sendThread = new SendThread();
        sendThread.start();
        receiveThread = new ReceiveThread();
        receiveThread.start();
        while(this.sent<sendbuffer.size()-1){
            try {
                Thread.sleep(100);
            }catch (InterruptedException e){
                System.out.println("thread  interrupted");
            }
        }

        sendThread.setClose();
        receiveThread.setClose();
        System.exit(0);
    }

    private void windowsend(LinkedList<GUDPbuffer>sendbuffer) throws IOException {
        for(int i=sent; i<min(sent+3,sendbuffer.size()); i++){
            System.out.println("sendbuffersize"+sendbuffer.size());
            System.out.println("i="+i+"sendbufferack"+sendbuffer.get(i).getACK());
            if(sendbuffer.get(i).getACK()==false){
                datagramSocket.send(sendbuffer.get(i).getGudpPacket().pack());
            }

        }

    }


    public void rearrange(LinkedList<GUDPbuffer> ackbuffer){
        int n = ackbuffer.size();
        if(n>0){
        for(int i = this.acksign;i<n;i++){
            for(int j = this.sent;j<this.sendbuffer.size();j++){
                if(this.sendbuffer.get(j).getGudpPacket().getSeqno()==ackbuffer.get(i).getGudpPacket().getSeqno()-1){
                    sendbuffer.get(j).setACK(true);
                }
            }
        }
        this.acksign = n;
        for(int k = 0; k<this.sendbuffer.size();k++) {
            if (this.sendbuffer.get(k).getACK() == false) {
                this.sent = k;
                break;
            }
        }
        }
    }

    public void close() throws IOException {
        ;
    }

    public int min(int a, int b){
        if(a>b){
            return b;
        }
        else {
            return a;
        }
    }
    class ReceiveThread2 extends Thread{
        boolean close = false;
        public void run(){
            while (!close){
                byte[] buf = new byte[GUDPPacket.MAX_DATAGRAM_LEN];
                DatagramPacket udppacket = new DatagramPacket(buf, buf.length);
                try {
                    datagramSocket.receive(udppacket);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                GUDPPacket gudppacket = null;
                try {
                    gudppacket = GUDPPacket.unpack(udppacket);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                if(gudppacket.getType()==GUDPPacket.TYPE_BSN){
                    priorityQueue.add(gudppacket);
                    sendacksign = gudppacket.getSeqno()+1;
                    try {
                        SendACK(gudppacket);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
                else if(gudppacket.getType()==GUDPPacket.TYPE_DATA){
                    if(gudppacket.getSeqno()==sendacksign){
                        priorityQueue.add(gudppacket);
                        try {
                            SendACK(gudppacket);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }


                    }
                }
                while (true){
                    GUDPPacket gudpPacket = priorityQueue.peek();
                    if (Objects.isNull(gudpPacket)) {
                        break;
                    }
                    if (gudpPacket.getSeqno() >= sendacksign) {
                        sendacksign++;
                        priorityQueue.remove();
                        receivequene.add(gudpPacket);
                    } else {
                        priorityQueue.remove();
                    }
                }
            }

        }



        public void setClose(){
            close = true;
        }
        public void SendACK(GUDPPacket packet) throws IOException {
            ByteBuffer buffer = ByteBuffer.allocate(GUDPPacket.HEADER_SIZE);
            buffer.order(ByteOrder.BIG_ENDIAN);
            GUDPPacket ackPacket = new GUDPPacket(buffer);
            ackPacket.setType(GUDPPacket.TYPE_ACK);
            ackPacket.setVersion(GUDPPacket.GUDP_VERSION);
            ackPacket.setSeqno(packet.getSeqno()+1);
            ackPacket.setPayloadLength(0);
            ackPacket.setSocketAddress(packet.getSocketAddress());
            datagramSocket.send(ackPacket.pack());
        }
    }

    //sendThread
    class SendThread extends Thread{
        boolean close = false;
        public void run(){
            while(!close){
                rearrange(ackbuffer);
                try{
                    SendThread.sleep(500);
                } catch (InterruptedException e){
                    throw new RuntimeException(e);
                }
                try {
                    windowsend(sendbuffer);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

        }
        public void setClose(){
            close = true;
        }

    }

    class ReceiveThread extends Thread{
        boolean close = false;
        public void run(){
            while (!close) {
                byte[] buf = new byte[GUDPPacket.MAX_DATAGRAM_LEN];
                DatagramPacket datapacket = new DatagramPacket(buf, buf.length);
                try {
                    datagramSocket.receive(datapacket);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                try {
                    GUDPPacket gudppacket = GUDPPacket.unpack(datapacket);
                    ackbuffer.add(new GUDPbuffer(gudppacket));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        public void setClose(){
            close = true;
        }

    }

    //GUDP buffer
    class GUDPbuffer{
        GUDPPacket gudpPacket;
        int time;
        boolean ACK = false;
        //ack is only used for receiving packet

        public GUDPbuffer(GUDPPacket gudpPacket){
            this.gudpPacket = gudpPacket;
        }

        public boolean getACK() {
            return ACK;
        }

        public void setACK(boolean ACK) {
            this.ACK = ACK;
        }

        public GUDPPacket getGudpPacket() {
            return gudpPacket;
        }
    }
}

