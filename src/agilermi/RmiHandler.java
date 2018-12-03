/**
 *  Copyright 2017 Salvatore Giamp�
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *  
 **/

package agilermi;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.NotSerializableException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import javax.net.SocketFactory;

import agilermi.filter.FilterFactory;

/**
 * 
 * @author Salvatore Giampa'
 *
 */
public class RmiHandler {

	/**
	 * static fields useful to retrieve wrappers from primitives and vice versa.
	 */
	private static final Map<Class<?>, Class<?>> PRIMITIVES_TO_WRAPPERS = new HashMap<Class<?>, Class<?>>();
	private static final Map<Class<?>, Class<?>> WRAPPERS_TO_PRIMITIVES = new HashMap<Class<?>, Class<?>>();
	static {
		PRIMITIVES_TO_WRAPPERS.put(boolean.class, Boolean.class);
		PRIMITIVES_TO_WRAPPERS.put(byte.class, Byte.class);
		PRIMITIVES_TO_WRAPPERS.put(char.class, Character.class);
		PRIMITIVES_TO_WRAPPERS.put(double.class, Double.class);
		PRIMITIVES_TO_WRAPPERS.put(float.class, Float.class);
		PRIMITIVES_TO_WRAPPERS.put(int.class, Integer.class);
		PRIMITIVES_TO_WRAPPERS.put(long.class, Long.class);
		PRIMITIVES_TO_WRAPPERS.put(short.class, Short.class);
		PRIMITIVES_TO_WRAPPERS.put(void.class, Void.class);

		WRAPPERS_TO_PRIMITIVES.put(Boolean.class, boolean.class);
		WRAPPERS_TO_PRIMITIVES.put(Byte.class, byte.class);
		WRAPPERS_TO_PRIMITIVES.put(Character.class, char.class);
		WRAPPERS_TO_PRIMITIVES.put(Double.class, double.class);
		WRAPPERS_TO_PRIMITIVES.put(Float.class, float.class);
		WRAPPERS_TO_PRIMITIVES.put(Integer.class, int.class);
		WRAPPERS_TO_PRIMITIVES.put(Long.class, long.class);
		WRAPPERS_TO_PRIMITIVES.put(Short.class, short.class);
		WRAPPERS_TO_PRIMITIVES.put(Void.class, void.class);
	}

	// connection details, socket and streams
	private InetSocketAddress inetSocketAddress;
	private Socket socket;
	private RmiObjectOutputStream out;
	private RmiObjectInputStream in;

	private RmiRegistry rmiRegistry;

	/**
	 * Map for invocations that are waiting a response
	 */
	private Map<Long, InvocationHandle> invocations = Collections.synchronizedMap(new HashMap<>());

	/**
	 * The queue for buffered invocations that are ready to be sent over the socket
	 */
	private BlockingQueue<Handle> invokeQueue = new ArrayBlockingQueue<>(200);

	/**
	 * Flag that indicates if this ObjectPeer has been disposed. When
	 */
	private boolean disposed = false;

	private Set<String> references = new TreeSet<>();

	/**
	 * Implements the flyweight pattern for stubs creation
	 */
	// private Map<StubKey, Object> stubFlyweight = Collections.synchronizedMap(new
	// HashMap<>());

	/**
	 * Connects to the selected address and port and creates a new ObjectPeer over
	 * that connection, with a new empty {@link ObjectRegistry}
	 * 
	 * @param address the address of the object server
	 * @param port    the port of the object server
	 * @return the ObjectPeer object representing the remote object server
	 * @throws UnknownHostException if the host cannot be found
	 * @throws IOException          if an I/O error occurs
	 */
	public static RmiHandler connect(String address, int port) throws UnknownHostException, IOException {
		return connect(address, port, new RmiRegistry(), null, null);
	}

	/**
	 * Connects to the selected address and port and creates a new RmiHandler over
	 * that connection, with the specified {@link RmiRegistry}
	 * 
	 * @param address     the address of the object server
	 * @param port        the port of the object server
	 * @param rmiRegistry the rmiRegistry that must be used by the RmiHandler
	 * @return the RmiHandler object representing the remote object server
	 * @throws UnknownHostException if the host cannot be found
	 * @throws IOException          if an I/O error occurs
	 */
	public static RmiHandler connect(String address, int port, RmiRegistry rmiRegistry, SocketFactory sFactory,
			FilterFactory filterFactory) throws UnknownHostException, IOException {
		Socket socket = null;
		if (sFactory != null)
			socket = sFactory.createSocket(address, port);
		else
			socket = new Socket(address, port);
		return new RmiHandler(socket, rmiRegistry, filterFactory);
	}

	/**
	 * Constructs a new RmiHandler over the connection specified by the given
	 * socket, with the specified {@link RmiRegistry}.
	 * 
	 * @param socket      the socket over which the ObjectPeer will be created
	 * @param rmiRegistry the {@link ObjectRegistry} to use
	 * @see RmiHandler#connect(String, int, RmiRegistry, SocketFactory,
	 *      FilterFactory)
	 * @see RmiHandler#connect(String, int)
	 * @throws IOException if an I/O error occurs
	 */
	public RmiHandler(Socket socket, RmiRegistry registry) throws IOException {
		this(socket, registry, null);
	}

	/**
	 * Constructs a new RmiHandler over the connection specified by the given
	 * socket, with the specified {@link RmiRegistry}.
	 * 
	 * @param socket        the socket over which the ObjectPeer will be created
	 * @param rmiRegistry   the {@link ObjectRegistry} to use
	 * @param filterFactory a {@link FilterFactory} that allows to add communication
	 *                      levels, such as levels for cryptography or data
	 *                      compression
	 * @see RmiHandler#connect(String, int, RmiRegistry, SocketFactory,
	 *      FilterFactory)
	 * @see RmiHandler#connect(String, int)
	 * @throws IOException if an I/O error occurs
	 */
	@SuppressWarnings("resource")
	public RmiHandler(Socket socket, RmiRegistry rmiRegistry, FilterFactory filterFactory) throws IOException {
		inetSocketAddress = new InetSocketAddress(socket.getInetAddress(), socket.getPort());
		this.socket = socket;
		this.rmiRegistry = rmiRegistry;

		OutputStream output = new BufferedOutputStream(socket.getOutputStream(), 512);
		InputStream input = new BufferedInputStream(socket.getInputStream(), 512);

		if (filterFactory != null) {
			output = filterFactory.buildOutputStream(output);
			input = filterFactory.buildInputStream(input);
		}

		out = new RmiObjectOutputStream(output, rmiRegistry);
		out.flush();

		in = new RmiObjectInputStream(input, rmiRegistry, inetSocketAddress);

		receiver.setDaemon(true);
		sender.setDaemon(true);
		receiver.start();
		sender.start();
	}

	/**
	 * Gets a stub for the specified object identifier respect to the specified
	 * interface, representing a remote object on the object server. This method
	 * performs no network operation, just creates the stub. All the interfaces
	 * passed must be visible in the class loader of the first interface.
	 * 
	 * @param objectId       the object identifier
	 * @param stubInterfaces the interface whose methods must be stubbed, that is
	 *                       the interface used to access the remote object
	 *                       operations
	 * @param                <T> the stub interface type
	 * @return A dynamic proxy object that represents the remote instance. It is an
	 *         instance for the specified stub interface
	 */
	public synchronized Object getStub(String objectId, Class<?>... stubInterfaces) {
		if (disposed)
			throw new IllegalStateException("This RmiHandler has been disposed");
		if (stubInterfaces.length == 0)
			throw new IllegalArgumentException("No interface has been passed");

		Object stub;
		stub = Proxy.newProxyInstance(stubInterfaces[0].getClassLoader(), stubInterfaces,
				new RemoteInvocationHandler(objectId, this));
		return stub;
	}

	/**
	 * Gets the rmiRegistry used by this ObjectPeer
	 * 
	 * @return the rmiRegistry used by this peer
	 */
	public RmiRegistry getObjectContext() {
		return rmiRegistry;
	}

	/**
	 * Gets the remote connection details of this peer
	 * 
	 * @return the {@link InetSocketAddress} containing remote host address and port
	 */
	public InetSocketAddress getInetSocketAddress() {
		return inetSocketAddress;
	}

	/**
	 * Dispose this ObjectPeer and frees all the used resources and threads. After
	 * the call to this method, the call to the
	 * {@link RmiHandler#getStub(String, Class)} method will result in an
	 * {@link IllegalStateException} and all the stubs generated by this
	 * {@link RmiHandler} object will not function properly. A call to this method
	 * cause a callback on the failure observers attached to the rmiRegistry sending
	 * them an instance of {@link RmiDispositionException}
	 */
	public synchronized void dispose() {
		if (disposed)
			return;

		disposed = true;
		receiver.interrupt();
		sender.interrupt();

		try {
			socket.close();
		} catch (IOException e) {
			e.printStackTrace();
		}

		// let the stubs to return
		for (Handle handle : invocations.values()) {
			if (handle instanceof InvocationHandle)
				forceInvocationReturn((InvocationHandle) handle);
			synchronized (handle) {
				handle.notifyAll();
			}
		}

		for (Iterator<String> it = references.iterator(); it.hasNext();) {
			Skeleton sk = rmiRegistry.getSkeleton(it.next());
			if (sk != null) {
				sk.removeAllRefs(this);
				it.remove();
			}
		}

		RmiDispositionException dispositionException = new RmiDispositionException();
		rmiRegistry.sendFailure(this, dispositionException);

		socket = null;
		out = null;
		in = null;
		invocations.clear();
		invokeQueue.clear();
	}

	private void forceInvocationReturn(InvocationHandle invocation) {
		synchronized (invocation) {
			if (rmiRegistry.isDispositionExceptionEnabled())
				invocation.thrownException = new RmiDispositionException();
			invocation.returned = true;
			invocation.notifyAll();
		}
	}

	/**
	 * Gets the disposed status of this peer
	 * 
	 * @return true if this peer has been disposed, false otherwise
	 */
	public boolean isDisposed() {
		return disposed;
	}

	/**
	 * Package-level operation used by stub invocation handlers to put new
	 * invocations
	 * 
	 * @param invocation the invocation request
	 * @throws InterruptedException
	 */
	void putHandle(Handle handle) throws InterruptedException {
		if (invokeQueue != null)
			invokeQueue.put(handle);
	}

	/**
	 * This is the thread that manages the output stream of the connection only. It
	 * send new method invocations to the other peer or the invocation results. It
	 * reads new invocations from the invokeQueue.
	 */
	private Thread sender = new Thread() {
		@Override
		public void run() {
			Handle handle = null;
			try {
				while (!isInterrupted()) {
					handle = null;
					handle = invokeQueue.take();

					if (handle instanceof InvocationHandle) {
						InvocationHandle invocation = (InvocationHandle) handle;
						try {
							out.writeUnshared(handle);
							invocations.put(invocation.id, invocation);
						} catch (NotSerializableException e) {
							invocation.thrownException = e;
							synchronized (invocation) {
								invocation.notifyAll();
							}
							throw e;
						}
					} else if (handle instanceof ReturnHandle) { // send
																	// invocation
																	// response
						ReturnHandle ret = (ReturnHandle) handle;
						try {
							out.writeUnshared(ret);
						} catch (NotSerializableException e) {
							ret.returnValue = null;
							ret.returnClass = null;
							ret.thrownException = e;
							out.writeUnshared(ret);
							e.printStackTrace();
						}

					} else {
						out.writeUnshared(handle);
					}

					out.flush();
				}
			} catch (IOException | InterruptedException e) { // something gone wrong, destroy the handler

				// e.printStackTrace();
				if (handle instanceof InvocationHandle) {
					InvocationHandle invocation = (InvocationHandle) handle;
					invocation.thrownException = e;

				}
				synchronized (handle) {
					handle.notifyAll();
				}

				if (disposed)
					return;

				dispose();

				e.printStackTrace();

				try {
					socket.close();
				} catch (Exception e1) {
				}

				rmiRegistry.sendFailure(RmiHandler.this, e);
			}
		}
	};
	/**
	 * This is the thread that manages the input stream of the connection only. It
	 * receives new method invocations by the other peer or the invocation results.
	 * In the first case it calls the method of the implementation object. In the
	 * second case it notifies the invocation handlers that are waiting for the
	 * remote method to return.
	 */
	private Thread receiver = new Thread() {
		@Override
		public void run() {
			try {
				while (!isInterrupted()) {
					Handle handle = (Handle) (in.readUnshared());
					if (handle instanceof InvocationHandle) {
						InvocationHandle invocation = (InvocationHandle) handle;
						new Thread(() -> {
							ReturnHandle retHandle = new ReturnHandle();
							retHandle.invocationId = invocation.id;
							try {

								Skeleton skeleton = rmiRegistry.getSkeleton(invocation.objectId);

								// retrieve the object
								Object object = skeleton.getObject();

								retHandle.returnValue = skeleton.invoke(invocation.method, invocation.parameterTypes,
										invocation.parameters);

								// find the correct method
								Method method = object.getClass().getMethod(invocation.method,
										invocation.parameterTypes);

								// set invocation return class
								retHandle.returnClass = method.getReturnType();

							} catch (InvocationTargetException e) {
								System.out.println("exception caught");
								e.printStackTrace();
								retHandle.thrownException = e.getCause();
							} catch (NoSuchMethodException e) {
								e.printStackTrace();
								retHandle.thrownException = new NoSuchMethodException("The method '" + invocation.method
										+ "(" + Arrays.toString(invocation.parameterTypes)
										+ ")' does not exists for the object with identifier '" + invocation.objectId
										+ "'.");
							} catch (SecurityException e) {
								e.printStackTrace();
								retHandle.thrownException = e;
							} catch (IllegalAccessException e) {
								e.printStackTrace();
							} catch (IllegalArgumentException e) {
								e.printStackTrace();
								retHandle.thrownException = e;
							} catch (NullPointerException e) {
								e.printStackTrace();
								retHandle.thrownException = new NullPointerException("The object identifier '"
										+ invocation.objectId + "' of the stub is not bound to a remote object");
							}

							// send invocation response after method execution
							try {
								invokeQueue.put(retHandle);
							} catch (InterruptedException e) {
							}

						}).start();
					} else if (handle instanceof ReturnHandle) {
						ReturnHandle ret = (ReturnHandle) handle;

						// remove the waiting invocation
						InvocationHandle invocation = invocations.remove(ret.invocationId);

						if (invocation != null) {

							// set return
							invocation.returnClass = ret.returnClass;
							invocation.returnValue = ret.returnValue;
							invocation.thrownException = ret.thrownException;
							invocation.returned = true;

							// notify the invocation handler that is waiting on it
							synchronized (invocation) {
								invocation.notifyAll();
							}
						}
					} else if (handle instanceof FinalizeHandle) {
						FinalizeHandle finHandle = (FinalizeHandle) handle;
						Skeleton sk = rmiRegistry.getSkeleton(finHandle.objectId);
						if (sk != null)
							sk.removeRef(RmiHandler.this);
					} else if (handle instanceof NewReferenceHandle) {
						NewReferenceHandle newReferenceHandle = (NewReferenceHandle) handle;
						if (newReferenceHandle.objectId != null) {
							Skeleton sk = rmiRegistry.getSkeleton(newReferenceHandle.objectId);
							sk.addRef(RmiHandler.this);
							references.add(sk.getId());
						}
					} else {
						throw new RuntimeException("AgileRMI INTERNAL ERROR");
					}

				}
			} catch (Exception e) { // something gone wrong, destroy the
									// ObjectPeer

				e.printStackTrace();

				if (disposed)
					return;

				dispose();

				try {
					socket.close();
				} catch (Exception e1) {
				}

				rmiRegistry.sendFailure(RmiHandler.this, e);
			}
		}
	};

}