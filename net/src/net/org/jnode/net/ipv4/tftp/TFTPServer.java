/*
 * $Id$
 */
package org.jnode.net.ipv4.tftp;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketException;

import org.apache.log4j.Logger;
import org.apache.commons.net.tftp.TFTP;
import org.apache.commons.net.tftp.TFTPPacket;
import org.apache.commons.net.tftp.TFTPAckPacket;
import org.apache.commons.net.tftp.TFTPDataPacket;
import org.apache.commons.net.tftp.TFTPReadRequestPacket;
import org.apache.commons.net.tftp.TFTPWriteRequestPacket;
import org.apache.commons.net.tftp.TFTPErrorPacket;
import org.apache.commons.net.tftp.TFTPPacketException;

/**
 * TFTP server.
 * Currently only supports one client at a time.
 * @author markhale
 */
public class TFTPServer extends TFTP {

	private final Logger log = Logger.getLogger(getClass());

	/** current client address */
	private InetAddress clientAddress;
	private int clientPort;
	/** send transfer stream */
	private FileInputStream fileIn;
	/** receive transfer stream */
	private FileOutputStream fileOut;
	/** current block number received or sent */
	private int blockNumber;

	public static void main(String[] args) {
		TFTPServer server = new TFTPServer();
		try {
			server.run();
		} catch(SocketException ex) {
			Logger.getLogger(TFTPServer.class).fatal("Socket exception", ex);
		}
	}

	private void run() throws SocketException {
		System.out.println("JNode TFTP Server");
		open(DEFAULT_PORT);
		setSoTimeout(0);
		beginBufferedOps();
		try {
			boolean doShutdown = false;
			while(!doShutdown) {
				try {
					TFTPPacket packet = bufferedReceive();
					processRequest(packet);
				} catch(TFTPPacketException ex) {
					log.debug("Error in TFTP packet", ex);
				} catch(IOException ex) {
					log.debug("I/O exception", ex);
				}
			}
		} finally {
			endBufferedOps();
			close();
		}
	}

	private void processRequest(TFTPPacket packet) throws IOException {
		log.debug("Received packet: "+packet.getAddress()+":"+packet.getPort()+" "+packet.toString());
		final int type = packet.getType();
		switch(type) {
			case TFTPPacket.WRITE_REQUEST:
				if(clientAddress == null) {
					TFTPWriteRequestPacket wreqPacket = (TFTPWriteRequestPacket) packet;
					fileOut = new FileOutputStream(wreqPacket.getFilename());
					blockNumber = 0;
					bufferedSend(new TFTPAckPacket(packet.getAddress(), packet.getPort(), blockNumber));
					clientAddress = packet.getAddress();
					clientPort = packet.getPort();
				}
				break;
			case TFTPPacket.DATA:
				if(packet.getAddress().equals(clientAddress) && packet.getPort() == clientPort) {
					TFTPDataPacket dataPacket = (TFTPDataPacket) packet;
					// if client sent next block
					if(dataPacket.getBlockNumber() == blockNumber+1) {
						fileOut.write(dataPacket.getData(), dataPacket.getDataOffset(), dataPacket.getDataLength());
						// send acknowledgement
						bufferedSend(new TFTPAckPacket(packet.getAddress(), packet.getPort(), dataPacket.getBlockNumber()));
						blockNumber++;
						// if last block then end of transfer
						if(dataPacket.getDataLength() < TFTPDataPacket.MAX_DATA_LENGTH) {
							fileOut.close();
							clientAddress = null;
							clientPort = 0;
						}
					}
				}
				break;
			case TFTPPacket.READ_REQUEST:
				if(clientAddress == null) {
					TFTPReadRequestPacket rreqPacket = (TFTPReadRequestPacket) packet;
					try {
						fileIn = new FileInputStream(rreqPacket.getFilename());
						blockNumber = 1;
						byte[] data = new byte[TFTPDataPacket.MAX_DATA_LENGTH];
						final int bytesRead = fileIn.read(data);
						bufferedSend(new TFTPDataPacket(packet.getAddress(), packet.getPort(), blockNumber, data, 0, bytesRead));
						// if more blocks to send
						if(bytesRead == TFTPDataPacket.MAX_DATA_LENGTH) {
							clientAddress = packet.getAddress();
							clientPort = packet.getPort();
						} else {
							fileIn.close();
						}
					} catch(FileNotFoundException ex) {
						bufferedSend(new TFTPErrorPacket(packet.getAddress(), packet.getPort(), TFTPErrorPacket.FILE_NOT_FOUND, ex.getMessage()));
					}
				}
				break;
			case TFTPPacket.ACKNOWLEDGEMENT:
				if(packet.getAddress().equals(clientAddress) && packet.getPort() == clientPort) {
					TFTPAckPacket ackPacket = (TFTPAckPacket) packet;
					// if client acknowledged last block
					if(ackPacket.getBlockNumber() == blockNumber) {
						// send next block
						byte[] data = new byte[TFTPDataPacket.MAX_DATA_LENGTH];
						final int bytesRead = fileIn.read(data);
						blockNumber++;
						bufferedSend(new TFTPDataPacket(packet.getAddress(), packet.getPort(), blockNumber, data, 0, bytesRead));
						// if last block then end of transfer
						if(bytesRead < TFTPDataPacket.MAX_DATA_LENGTH) {
							fileIn.close();
							clientAddress = null;
							clientPort = 0;
						}
					}
				}
				break;
		}
	}
}
