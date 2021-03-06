/**
 * (c) 2016 SAP SE or an SAP affiliate company. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package com.altiscale.TcpProxy;

import org.apache.log4j.Logger;

import java.io.IOException;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.Thread;
import java.net.Socket;
import java.util.ArrayList;

import com.altiscale.Util.SecondMinuteHourCounter;
import com.altiscale.TcpProxy.Server;

/**
 * TcpTunnel is a class to handle data transfer between incomming-outgoing socket pairs (tunnels)
 * and a threadpool that serves all these existing tunnels.
 *
 * Goal of TcpTunnel is to simply tunnel all data between incoming sockets and their destination
 * sockets, trying to load-balance so that each destination connection transfers data with equal
 * rate.
 *
 * Destination ports are usually ssh-tunnels to a same service.
 *
 */
public class TcpTunnel {
  // log4j logger.
  private static Logger LOG = Logger.getLogger("TransferAccelerator");

  // Socket from our client to us.
  private Socket clientSocket;

  // Socket from us to our server.
  private Socket serverSocket;

  // Client-to-Server one-directional tunnel.
  OneDirectionTunnel clientServer;

  // Server-to-Client one-directional tunnel.
  OneDirectionTunnel serverClient;

  private Server server;

  // We are just a proxy. We create two pipes, proxy all data and whoever closes the
  // connection first our job is to simply close the other end as well.
  protected class OneDirectionTunnel implements Runnable {
    private String threadName;
    private Thread thread;

    private Socket sourceSocket;
    private Socket destinationSocket;

    private SecondMinuteHourCounter byteRateCnt;

    private Server proxyServer;

    /**
     *  OneDirectionalTunnel is responsible for reading on its source socket and writing
     *  all data to its destination socket. It is blocking, so it runs in its own thread.
     *
     *  @param source       Socket from which we read data
     *  @param destination  Socket to which we write data
     *  @param name         Thread name for the thread we'll create when started.
     *  @param proxyServer  Referece used to aggregate byte rates/opened connections/
     *                      closed connections per server.
     */
    public OneDirectionTunnel(Socket source, Socket destination, String name,
                              Server proxyServer) {
      threadName = name;
      thread = null;
      sourceSocket = source;
      destinationSocket = destination;
      byteRateCnt = new SecondMinuteHourCounter(name + " byteRateCnt");
      this.proxyServer = proxyServer;
    }

    /*
     *  Method to create new thread which will run() our tunnel.
     *
     *  @return  Thread in which we're running.
     */
    public Thread start() {
      assert null == thread;  // we should never call this method twice.
      LOG.debug("Starting thread [" + threadName + "]");
      thread = new Thread(this, threadName);
      thread.start();
      return thread;
    }

    /*
     *  We open input and output streams, read the input and then write all data to output.
     *  If anything happens we simply close the sockets and finish.
     */
    public void run() {
      DataInputStream input = null;
      DataOutputStream output = null;
      try {
        input = new DataInputStream(sourceSocket.getInputStream());
        output = new DataOutputStream(destinationSocket.getOutputStream());
      } catch (IOException ioe) {
        LOG.error("Could not open input or output stream.");
        return;
      }
      proxyServer.incrementOpenedConn();
      int cnt = 0;
      byte[] buffer = new byte[1024 * 8];  // 8KB buffer.
      try {
        do {

          // Read some data.
          cnt = input.read(buffer);

          if (cnt > 0) {
            output.write(buffer, 0, cnt);

            // NOTE: if this becomes expensive, we can increment counter and flush less often.
            byteRateCnt.incrementBy(cnt);
            proxyServer.incrementByteRateBy(cnt);
            output.flush();
          }
        } while (cnt >= 0);
      } catch (IOException ioe) {
        LOG.debug("Closing socket after IO exception while reading: " + ioe.getMessage());
      }
      // Either the input stream is closed or we got an exception. Either way, close the
      // sockets since we're done with this tunnel.
      try {
        closeConnection();
        proxyServer.incrementClosedConn();
      } catch (IOException ioe) {
        LOG.error("IO exception while closing sockets in thread [" + threadName +
            "]: " + ioe.getMessage());
      }

      LOG.debug(byteRateCnt.toString());

      LOG.debug("Exiting thread [" + threadName + "]");
    }

    public void closeConnection() throws IOException {
      if (!sourceSocket.isClosed()) {
        sourceSocket.close();
      }
      if (!destinationSocket.isClosed()) {
        destinationSocket.close();
      }
    }
  }

  /*
   *  TcpTunnel creates two pipes, connecting client and server in both directions.
   *
   *  @param  client  Socket connected to our client
   *  @param  server  Socket connected to server selected for this client by proxy
   */
  public TcpTunnel(Socket client, Socket server,
                   Server proxyServer) {
    clientSocket = client;
    serverSocket = server;

    // Create two one-directional tunnels to connect both pipes.
    clientServer = new OneDirectionTunnel(clientSocket, serverSocket, "clientServer", proxyServer);
    serverClient = new OneDirectionTunnel(serverSocket, clientSocket, "serverClient", proxyServer);
  }

  /*
   *  Starts data tunneling in two OneDirectionTunnel threads.
   */
  public void spawnTunnelThreads() {
    // Start both of them in their own threads.
    clientServer.start();
    serverClient.start();
  }
}
