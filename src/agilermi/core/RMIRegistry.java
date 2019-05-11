/**
 *  Copyright 2018-2019 Salvatore Giamp�
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

package agilermi.core;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;

import agilermi.authentication.RMIAuthenticator;
import agilermi.codemobility.ClassLoaderFactory;
import agilermi.codemobility.URLClassLoaderFactory;
import agilermi.communication.ProtocolEndpoint;
import agilermi.communication.ProtocolEndpointFactory;
import agilermi.configuration.RMIFaultHandler;
import agilermi.configuration.Remote;
import agilermi.exception.RemoteException;

/**
 * Defines a class that accepts new TCP connections over a port of the local
 * machine and automatically creates and manages the object sockets to export
 * remote objects. It integrates a registry of exported objects. The instances
 * of this class can be shared among more than one RMIHandler to obtain a
 * multi-peer interoperability. This class can be instantiated through its
 * {@link Builder} whose instances are constructed by the {@link #builder()}
 * factory method.<br>
 * <br>
 * Instantiation example:<br>
 * <code>RMIRegistry registry = RMIRegistry.builder().build();</code>
 * 
 * @author Salvatore Giampa'
 *
 */
public final class RMIRegistry {

	// executor service used to move network operations on other threads
	ScheduledExecutorService executorService = Executors
			.newScheduledThreadPool(Runtime.getRuntime().availableProcessors() + 1, new ThreadFactory() {
				@Override
				public Thread newThread(Runnable task) {
					Thread th = new Thread(task);
					th.setDaemon(true);
					th.setName("RMIRegistry.executorService");
					return th;
				}
			});

	// identifies the current instance of RMIRegistry. It serves to avoid loop-back
	// connections and to replace remote pointer that point to an object on this
	// same registry with their local instance
	final String registryKey;

	private boolean unmodifiable = false;

	// lock for synchronized access to this instance
	private Object lock = new Object();

	// codebases for code mobility
	RMIClassLoader rMIClassLoader;

	private boolean codeMobilityEnabled = false;

	// the server socket created by the last call to the enableListener() method
	private ServerSocket serverSocket;

	// the reference to the main thread
	private Thread listener;

	// the peer that are currently online
	private Map<InetSocketAddress, List<RMIHandler>> handlers = new HashMap<>();

	// port of the TCP listener
	private int listenerPort;

	// socket factories
	private ServerSocketFactory serverSocketFactory;
	private SocketFactory socketFactory;

	// failure observers
	private Set<RMIFaultHandler> rMIFaultHandlers = new HashSet<>();

	// enable the stubs to throw a remote exception when invoked after a connection
	// failure
	private boolean remoteExceptionEnabled = true;

	// automatically referenced interfaces
	private Set<Class<?>> remotes = new HashSet<>();

	// map: object -> skeleton
	Map<Object, Skeleton> skeletonByObject = new IdentityHashMap<>();

	// map: identifier -> skeleton
	Map<String, Skeleton> skeletonById = new HashMap<>();

	// filter factory used to customize network communication streams
	private ProtocolEndpointFactory protocolEndpointFactory;

	// multiple connection mode
	private boolean multiConnectionMode = false;

	// map: "address:port" -> "authIdentifier:authPassphrase"
	private Map<String, String[]> authenticationMap = new TreeMap<>();

	private int dgcLeaseValue = 30000;

	private ClassLoaderFactory classLoaderFactory;

	/**
	 * Defines the main thread that accepts new incoming connections and creates
	 * {@link RMIHandler} objects
	 */
	private Runnable listenerTask = new Runnable() {
		@Override
		public void run() {
			Thread.currentThread().setName("RMIRegistry.listenerTask");
			while (listener != null && !listener.isInterrupted())
				try {
					Socket socket = serverSocket.accept();
					RMIHandler rMIHandler = new RMIHandler(socket, RMIRegistry.this, protocolEndpointFactory);
					if (!handlers.containsKey(rMIHandler.getInetSocketAddress()))
						handlers.put(rMIHandler.getInetSocketAddress(), new ArrayList<>(1));
					handlers.get(rMIHandler.getInetSocketAddress()).add(rMIHandler);
					rMIHandler.start();
				} catch (IOException e) {
					// e.printStackTrace();
				}
		};
	};

	private boolean finalized = false;

	/**
	 * Defines the {@link RMIFaultHandler} used to manage the peer which closed the
	 * connection
	 */
	RMIFaultHandler rMIFaultHandler = new RMIFaultHandler() {
		@Override
		public void onFault(RMIHandler rMIHandler, Exception exception) {
			if (finalized)
				return;
			List<RMIHandler> list = handlers.get(rMIHandler.getInetSocketAddress());
			if (list != null)
				list.remove(rMIHandler);
		}
	};

	// rMIAuthenticator objects that authenticates and authorize users
	private RMIAuthenticator rMIAuthenticator;

	/**
	 * Package-level method to get authentication relative to a remote process.
	 * 
	 * @param host the remote host
	 * @param port the remote port
	 * 
	 * @return String array that has authentication identifier at the 0 position and
	 *         the authentication pass-phrase at the 1 position or null if no
	 *         authentication has been specified for the rmeote process
	 * 
	 */
	String[] getAuthentication(String host, int port) {
		try {
			InetAddress address = InetAddress.getByName(host);
			host = address.getCanonicalHostName();
		} catch (UnknownHostException e) {
		}
		String key = host + ":" + port;
		return authenticationMap.get(key);
	}

	/**
	 * Package-level method to get authentication relative to a remote process.
	 * 
	 * @param address the remote address
	 * @param port    the remote port
	 * 
	 * @return String array that has authentication identifier at the 0 position and
	 *         the authentication pass-phrase at the 1 position or null if no
	 *         authentication has been specified for the rmeote process
	 */
	String[] getAuthentication(InetAddress address, int port) {
		String host = address.getCanonicalHostName();
		String key = host + ":" + port;
		return authenticationMap.get(key);
	}

	/**
	 * Creates a new {@link RMIRegistry.Builder} instance, used to configure and
	 * start a new {@link RMIRegistry} instance.
	 * 
	 * @return a new {@link RMIRegistry.Builder} instance
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for {@link RMIRegistry}. A new instance of this class can be returned
	 * by the {@link RMIRegistry#builder()} static method. A new instance of this
	 * class wraps all the defaults for {@link RMIRegistry} and allows to modify
	 * them. When the configuration has been terminated, a new {@link RMIRegistry}
	 * instance can be obtained by the {@link Builder#build()} method.
	 * 
	 * defaults:<br>
	 * <ul>
	 * <li>connection listener enabled: false</li>
	 * <li>connection listener daemon: true</li>
	 * <li>{@link ServerSocketFactory}: null (that is
	 * {@link ServerSocketFactory#getDefault()})</li>
	 * <li>{@link SocketFactory}: null (that is
	 * {@link SocketFactory#getDefault()})</li>
	 * <li>{@link ProtocolEndpointFactory}: null</li>
	 * <li>{@link RMIAuthenticator}: null</li>
	 * <li>authentication Identifier: null (that is guest identifier)</li>
	 * <li>authentication pass-phrase: null (that is guest pass-phrase)</li>
	 * </ul>
	 * 
	 * @author Salvatore Giampa'
	 *
	 */
	public static class Builder {

		private Builder() {
		}

		// underlyng protocols
		private ServerSocketFactory serverSocketFactory = ServerSocketFactory.getDefault();
		private SocketFactory socketFactory = SocketFactory.getDefault();
		private ProtocolEndpointFactory protocolEndpointFactory;

		// authentication
		private RMIAuthenticator rMIAuthenticator;

		// code mobility
		private Set<URL> codebases = new HashSet<>();
		private boolean codeMobilityEnabled = false;
		private ClassLoaderFactory classLoaderFactory;

		private int dgcLeaseValue = 30000;

		private Collection<String> authentications = new HashSet<>();

		private boolean multiConnectionMode = false;

		private boolean unmodifiable = false;

		/**
		 * Sets the lease timeout after that the distributed garbage collection
		 * mechanism will remove a non-named object from the registry.
		 * 
		 * @param dgcLeaseValue the lease timeout value in milliseconds
		 */
		public void setDgcLeaseValue(int dgcLeaseValue) {
			this.dgcLeaseValue = dgcLeaseValue;
		}

		/**
		 * Utility method to add a codebase at building time. Codebases can be added and
		 * removed after the registry construction, too.
		 * 
		 * @param url the url to the codebase
		 * @return this builder
		 */
		public Builder addCodebase(URL url) {
			codebases.add(url);
			return this;
		}

		/**
		 * Utility method to add codebases at building time. Codebases can be added and
		 * removed after the registry construction, too.
		 * 
		 * @param urls the urls of the codebases
		 * @return this builder
		 */
		public Builder addCodebases(Iterable<URL> urls) {
			if (urls == null)
				return this;
			for (URL url : urls)
				codebases.add(url);
			return this;
		}

		/**
		 * Utility method to add codebases at building time. Codebases can be added and
		 * removed after the registry construction, too.
		 * 
		 * @param urls the urls of the codebases
		 * @return this builder
		 */
		public Builder addCodebases(URL... urls) {
			if (urls == null)
				return this;
			for (URL url : urls)
				codebases.add(url);
			return this;
		}

		/**
		 * Set the class loader factory used by this registry to decode remote classes
		 * when code mobility is enabled. It is necessary on some platforms that uses a
		 * different implementation of the Java Virtual Machine.
		 * 
		 * @param classLoaderFactory the factory to use
		 * 
		 * @return this builder
		 */
		public Builder setClassLoaderFactory(ClassLoaderFactory classLoaderFactory) {
			this.classLoaderFactory = classLoaderFactory;
			return this;
		}

		/**
		 * Enable code mobility
		 * 
		 * @param codeMobilityEnabled true if code mobility must be enabled, false
		 *                            otherwise
		 * @return this builder
		 */
		public Builder enableCodeMobility(boolean codeMobilityEnabled) {
			this.codeMobilityEnabled = codeMobilityEnabled;
			return this;
		}

		/**
		 * Set an {@link RMIAuthenticator} object that intercept authentication and
		 * authorization requests from remote machines.
		 * 
		 * @param rMIAuthenticator the {@link RMIAuthenticator} instance to use
		 * @return this builder
		 */
		public Builder setAuthenticator(RMIAuthenticator rMIAuthenticator) {
			this.rMIAuthenticator = rMIAuthenticator;
			return this;
		}

		/**
		 * 
		 * Sets the socket factories that the registry will use.
		 * 
		 * @param socketFactory       the {@link SocketFactory} instance to use to build
		 *                            client sockets
		 * @param serverSocketFactory the {@link ServerSocketFactory} instance to use to
		 *                            build the listener server socket
		 * @return this builder
		 */
		public Builder setSocketFactories(SocketFactory socketFactory, ServerSocketFactory serverSocketFactory) {
			this.socketFactory = socketFactory;
			this.serverSocketFactory = serverSocketFactory;
			return this;
		}

		/**
		 * Sets the {@link ProtocolEndpointFactory} instance to use in the registry to
		 * build.
		 * 
		 * @param protocolEndpointFactory the {@link ProtocolEndpointFactory} instance
		 *                                that gives the {@link ProtocolEndpoint}
		 *                                instance in which the underlying communication
		 *                                streams will be wrapped in
		 * @return this builder
		 */
		public Builder setProtocolEndpointFactory(ProtocolEndpointFactory protocolEndpointFactory) {
			this.protocolEndpointFactory = protocolEndpointFactory;
			return this;
		}

		/**
		 * Builds the RMIRegistry. After this call, the builder will not reset.
		 * 
		 * @return the built {@link RMIRegistry} instance
		 */
		public RMIRegistry build() {
			RMIRegistry rMIRegistry = new RMIRegistry(serverSocketFactory, socketFactory, protocolEndpointFactory,
					rMIAuthenticator, codeMobilityEnabled, codebases, classLoaderFactory, dgcLeaseValue);
			return rMIRegistry;
		}
	}

	/**
	 * Creates a new {@link RMIRegistry} with the given ServerSocketFactory,
	 * SocketFactory and ProtocolEndpointFactory instances, without starting the
	 * connection listener.
	 * 
	 * @param serverSocketFactory     the {@link ServerSocketFactory} instance to
	 *                                use to build the listener server socket
	 * @param socketFactory           the {@link SocketFactory} instance to use to
	 *                                build client sockets
	 * @param protocolEndpointFactory the {@link ProtocolEndpointFactory} instance
	 *                                that gives the streams in which the underlying
	 *                                communication streams will be wrapped in
	 * @param rMIAuthenticator        an {@link RMIAuthenticator} instance that
	 *                                allows to authenticate and authorize users of
	 *                                incoming connection. For example, this
	 *                                instance can be an adapter that access a
	 *                                database or another pre-made authentication
	 *                                system.
	 * @param classLoaderFactory2
	 * 
	 * @see RMIRegistry.Builder
	 * @see RMIRegistry#builder()
	 */
	private RMIRegistry(ServerSocketFactory serverSocketFactory, SocketFactory socketFactory,
			ProtocolEndpointFactory protocolEndpointFactory, RMIAuthenticator rMIAuthenticator,
			boolean codeMobilityEnabled, Set<URL> codebases, ClassLoaderFactory classLoaderFactory, int dgcLeaseValue) {
		if (serverSocketFactory == null)
			serverSocketFactory = ServerSocketFactory.getDefault();
		if (socketFactory == null)
			socketFactory = SocketFactory.getDefault();

		Random random = new Random();
		this.registryKey = Long.toHexString(random.nextLong()) + Long.toHexString(random.nextLong())
				+ Long.toHexString(random.nextLong()) + Long.toHexString(random.nextLong())
				+ Long.toHexString(random.nextLong());

		this.serverSocketFactory = serverSocketFactory;
		this.socketFactory = socketFactory;
		this.protocolEndpointFactory = protocolEndpointFactory;
		this.rMIAuthenticator = rMIAuthenticator;
		this.codeMobilityEnabled = codeMobilityEnabled;
		if (classLoaderFactory == null)
			classLoaderFactory = new URLClassLoaderFactory();
		this.classLoaderFactory = classLoaderFactory;
		this.rMIClassLoader = new RMIClassLoader(codebases, classLoaderFactory);
		this.dgcLeaseValue = dgcLeaseValue;
	}

	/**
	 * Gets the class loader factory used by this registry to decode remote classes
	 * when code mobility is enabled.
	 * 
	 * @return the {@link ClassLoaderFactory} used by this registry
	 */
	public ClassLoaderFactory getClassLoaderFactory() {
		return classLoaderFactory;
	}

	/**
	 * Gets the lease timeout after that the distributed garbage collection
	 * mechanism will remove a non-named object from the registry.
	 * 
	 * @return the lease timeout value in milliseconds
	 */
	public int getDgcLeaseValue() {
		return dgcLeaseValue;
	}

	/**
	 * Sets the lease timeout after that the distributed garbage collection
	 * mechanism will remove a non-named object from the registry.
	 * 
	 * @param dgcLeaseValue the lease timeout value in milliseconds
	 */
	public void setDgcLeaseValue(int dgcLeaseValue) {
		this.dgcLeaseValue = dgcLeaseValue;
	}

	/**
	 * This will return all the static codebases and all the received codebases
	 * whose classes are currently instantiated.
	 * 
	 * @return all actually used codebases
	 */
	public Set<URL> getCodebases() {
		return rMIClassLoader.getCodebases();
	}

	/**
	 * Add new static codebases that will be sent to the other machines.
	 * 
	 * @param urls the codebases to add
	 */
	public void addCodebases(Iterable<URL> urls) {
		if (urls == null)
			return;
		for (URL url : urls)
			rMIClassLoader.addCodebase(url);
	}

	/**
	 * Add new static codebases that will be sent to the other machines.
	 * 
	 * @param urls the codebases to add
	 */
	public void addCodebases(URL... urls) {
		if (urls == null)
			return;
		for (URL url : urls)
			rMIClassLoader.addCodebase(url);
	}

	/**
	 * Add a new static codebase that will be sent to the other machines.
	 * 
	 * @param url the codebase to add
	 */
	public void addCodebase(URL url) {
		if (url == null)
			return;
		rMIClassLoader.addCodebase(url);
	}

	public void removeCodebase(URL url) {
		rMIClassLoader.removeCodebase(url);
	}

	/**
	 * Returns the {@link RMIClassLoader} instance used to load classes from remote
	 * codebase. This instance can be used to load specific classes that are not in
	 * the current classpath.
	 * 
	 * @return the {@link RMIClassLoader} instance used by this registry
	 */
	public RMIClassLoader getRmiClassLoader() {
		return rMIClassLoader;
	}

	/**
	 * Gets the code mobility enable flag
	 * 
	 * @return true if this registry accepts code from remote codebases, false
	 *         otherwise
	 */
	public boolean isCodeMobilityEnabled() {
		return codeMobilityEnabled;
	}

	/**
	 * Set the code mobility enable flag.
	 * 
	 * @param codeMobilityEnabled if true, this registry will accept code from
	 *                            remote codebases. Set it to false otherwise.
	 */
	public void setCodeMobilityEnabled(boolean codeMobilityEnabled) {
		this.codeMobilityEnabled = codeMobilityEnabled;
	}

	/**
	 * Adds authentication details for a remote host
	 * 
	 * @param host           the remote host
	 * @param port           the remote port
	 * @param authId         the authentication identifier
	 * @param authPassphrase the authentication pass-phrase
	 */
	public void setAuthentication(String host, int port, String authId, String authPassphrase) {
		try {
			InetAddress address = InetAddress.getByName(host);
			host = address.getCanonicalHostName();
		} catch (UnknownHostException e) {
		}
		String key = host + ":" + port;
		String[] auth = new String[] { authId, authPassphrase };
		authenticationMap.put(key, auth);
	}

	/**
	 * Removes authentication details for a remote host
	 * 
	 * @param host the remote host
	 * @param port the remote port
	 */
	public void removeAuthentication(String host, int port) {
		try {
			InetAddress address = InetAddress.getByName(host);
			host = address.getCanonicalHostName();
		} catch (UnknownHostException e) {
		}
		String key = host + ":" + port;
		authenticationMap.remove(key);
	}

	/**
	 * Finalizes this registry instance and all its current open connections. This
	 * method is also called by the Garbage Collector when the registry is no longer
	 * referenced
	 */
	@Override
	public void finalize() {
		finalize(true);
	}

	/**
	 * Finalizes this registry instance and all its current open connections. This
	 * method is also called by the Garbage Collector when the registry is no longer
	 * referenced
	 * 
	 * @param signalHandlersFailures set to true if you want all the
	 *                               {@link RMIHandler} instances created by this
	 *                               registry to send a signal to the failure
	 *                               observers
	 */
	public void finalize(boolean signalHandlersFailures) {
		synchronized (lock) {
			disableListener();

			finalized = true;
			for (Iterator<InetSocketAddress> it = handlers.keySet().iterator(); it.hasNext(); it.remove())
				for (RMIHandler rMIHandler : handlers.get(it.next()))
					rMIHandler.dispose(signalHandlersFailures);
		}
	}

	/**
	 * Shows the multi-connection mode enable state. If it is enabled, tends to
	 * create new connections for each created or received stub. By default it is
	 * disabled.
	 * 
	 * @return true if multi-connection mode is enabled, false otherwise
	 */
	public boolean isMultiConnectionMode() {
		return multiConnectionMode;
	}

	/**
	 * Enable or disable multi-connection mode. If it is enabled, tends to create
	 * new connections for each created or received stub. By default it is disabled.
	 * 
	 * @param multiConnectionMode true to enable, false to disable
	 */
	public void setMultiConnectionMode(boolean multiConnectionMode) {
		this.multiConnectionMode = multiConnectionMode;
	}

	/**
	 * Gets the stub for the specified object identifier on the specified host
	 * respect to the given interface. This method creates a new {@link RMIHandler}
	 * if necessary to communicate with the specified host. The new
	 * {@link RMIHandler} can be obtained by calling the
	 * {@link #getRMIHandler(String, int)} method.
	 * 
	 * @param address        the host address
	 * @param port           the host port
	 * @param objectId       the remote object identifier
	 * @param stubInterfaces the interfaces implemented by the stub
	 * @return the stub object
	 * @throws UnknownHostException if the host address cannot be resolved
	 * @throws IOException          if I/O errors occur
	 */
	public Object getStub(String address, int port, String objectId, Class<?>... stubInterfaces) throws IOException {
		synchronized (lock) {
			return getRMIHandler(address, port, multiConnectionMode).getStub(objectId, stubInterfaces);
		}
	}

	/**
	 * Gets the stub for the specified object identifier on the specified host
	 * respect to the given interface. This method creates a new {@link RMIHandler}
	 * if necessary to communicate with the specified host. The new
	 * {@link RMIHandler} can be obtained by calling the
	 * {@link #getRMIHandler(String, int)} method.
	 * 
	 * @param address        the host address
	 * @param port           the host port
	 * @param objectId       the remote object identifier
	 * @param newConnection  always create a new handler without getting an already
	 *                       existing one. This parameter overrides the
	 *                       {@link #isMultiConnectionMode} attribute
	 * @param stubInterfaces the interfaces implemented by the stub
	 * @return the stub object
	 * @throws UnknownHostException if the host address cannot be resolved
	 * @throws IOException          if I/O errors occur
	 */
	public Object getStub(String address, int port, String objectId, boolean newConnection, Class<?>... stubInterfaces)
			throws IOException {
		synchronized (lock) {
			return getRMIHandler(address, port, newConnection).getStub(objectId, stubInterfaces);
		}
	}

	/**
	 * Gets a stub for the specified object identifier representing a remote object
	 * on a remote machine. This method performs a request to the remote machine to
	 * get the remote interfaces of the remote object, then creates its stub. All
	 * the remote interfaces of the remote object must be visible by the default
	 * class loader and they must be known by the local runtime.
	 * 
	 * @param address  the host address
	 * @param port     the host port
	 * @param objectId the object identifier
	 * @return A dynamic proxy object that represents the remote instance. It is an
	 *         instance for the specified stub interface
	 * 
	 * @throws UnknownHostException if the host cannot be resolved
	 * @throws IOException          if an I/O error occurs
	 * @throws InterruptedException if the current thread is iterrupted during
	 *                              operation
	 */
	public Object getStub(String address, int port, String objectId)
			throws UnknownHostException, IOException, InterruptedException {
		return getStub(address, port, objectId, multiConnectionMode);
	}

	/**
	 * Gets a stub for the specified object identifier representing a remote object
	 * on a remote machine. This method performs a request to the remote machine to
	 * get the remote interfaces of the remote object, then creates its stub. All
	 * the remote interfaces of the remote object must be visible by the default
	 * class loader and they must be known by the local runtime.
	 * 
	 * @param address          the host address
	 * @param port             the host port
	 * @param objectId         the object identifier
	 * @param createNewHandler always create a new handler without getting an
	 *                         already existing one. This parameter overrides the
	 *                         {@link #isMultiConnectionMode} attribute
	 * @return A dynamic proxy object that represents the remote instance. It is an
	 *         instance for the specified stub interface
	 * 
	 * @throws UnknownHostException if the host cannot be resolved
	 * @throws IOException          if an I/O error occurs
	 * @throws InterruptedException if the current thread is iterrupted during
	 *                              operation
	 */
	public Object getStub(String address, int port, String objectId, boolean createNewHandler)
			throws UnknownHostException, IOException, InterruptedException {

		RMIHandler rmiHandler = getRMIHandler(address, port, createNewHandler);
		return rmiHandler.getStub(objectId);
	}

	/**
	 * Gets an {@link RMIHandler} instance for the specified host. If it has not
	 * been created, creates it. If some RMIHandler already exists, gets one of
	 * them.
	 * 
	 * @param host the host address
	 * @param port the host port
	 * @return the object peer related to the specified host
	 * @throws UnknownHostException if the host address cannot be resolved
	 * @throws IOException          if I/O errors occur
	 */
	public RMIHandler getRMIHandler(String host, int port) throws IOException {
		return getRMIHandler(host, port, false);
	}

	/**
	 * Gets an {@link RMIHandler} instance for the specified host. If it has not
	 * been created, creates it.
	 * 
	 * @param host          the host address
	 * @param port          the host port
	 * @param newConnection always create a new handler without getting an already
	 *                      existing one
	 * @return the object peer related to the specified host
	 * @throws UnknownHostException if the host address cannot be resolved
	 * @throws IOException          if I/O errors occurs
	 */
	public RMIHandler getRMIHandler(String host, int port, boolean newConnection) throws IOException {
		Callable<RMIHandler> callable = () -> {
			synchronized (handlers) {
				InetSocketAddress inetAddress = new InetSocketAddress(host, port);
				if (!handlers.containsKey(inetAddress))
					handlers.put(inetAddress, new ArrayList<>(1));
				List<RMIHandler> rMIHandlers = handlers.get(inetAddress);
				RMIHandler rMIHandler = null;
				if (rMIHandlers.size() == 0 || newConnection) {
					rMIHandler = new RMIHandler(socketFactory.createSocket(host, port), RMIRegistry.this,
							protocolEndpointFactory);
					rMIHandlers.add(rMIHandler);
					rMIHandler.start();
				}
				rMIHandler = rMIHandlers.get(0);
				return rMIHandler;
			}
		};

		Future<RMIHandler> future = executorService.submit(callable);
		try {
			return future.get();
		} catch (InterruptedException e) {
			return null;
		} catch (ExecutionException e) {
			throw (IOException) e.getCause().fillInStackTrace();
		}
	}

	public void closeAllConnections(String host, int port) throws IOException {
		Callable<Void> callable = () -> {
			synchronized (handlers) {
				InetSocketAddress inetAddress = new InetSocketAddress(host, port);
				if (!handlers.containsKey(inetAddress))
					return null;
				List<RMIHandler> rMIHandlers = handlers.get(inetAddress);
				for (RMIHandler hnd : rMIHandlers) {
					hnd.dispose(true);
				}
				rMIHandlers.clear();
			}
			return null;
		};

		Future<Void> future = executorService.submit(callable);
		try {
			future.get();
		} catch (InterruptedException e) {
			return;
		} catch (ExecutionException e) {
			throw (IOException) e.getCause().fillInStackTrace();
		}
	}

	/**
	 * Enable the registry listener on the selected port. This method enables the
	 * registry to accept new external incoming connections for RMI
	 * 
	 * @param port   the port to start the listener on
	 * @param daemon if true, the listener is started as daemon, that is it will be
	 *               stopped when all other non-daemon threads in the application
	 *               will bterminated.
	 * @throws IOException if I/O errors occur
	 */
	public void enableListener(int port, boolean daemon) throws IOException {
		Callable<Void> callable = () -> {
			synchronized (serverSocketFactory) {
				if (listener != null)
					disableListener();
				serverSocket = serverSocketFactory.createServerSocket(port);
				this.listenerPort = serverSocket.getLocalPort();
				listener = new Thread(listenerTask);
				listener.setDaemon(daemon);
				listener.start();
			}
			return null;
		};
		try {
			executorService.submit(callable).get();
		} catch (InterruptedException e) {
		} catch (ExecutionException e) {
			throw (IOException) e.getCause();
		}
	}

	/**
	 * Disable the registry listener. This method will disallow the registry to
	 * accept new incoming connections, but does not close the current open ones.
	 */
	public void disableListener() {
		Callable<Void> callable = () -> {
			synchronized (serverSocketFactory) {
				if (listener == null)
					return null;

				listener.interrupt();
				listener = null;
				try {
					serverSocket.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
				listenerPort = 0;
			}
			return null;
		};
		try {
			executorService.submit(callable).get();
		} catch (InterruptedException e) {
		} catch (ExecutionException e) {
		}
	}

	/**
	 * Gets the port on which the listener was started on last time
	 * 
	 * @return The last listener TCP port
	 */
	public int getListenerPort() {
		return listenerPort;
	}

	/**
	 * Gets the rMIAuthenticator of this registry
	 * 
	 * @return the rMIAuthenticator associated to this registry
	 */
	public RMIAuthenticator getAuthenticator() {
		return rMIAuthenticator;
	}

	/**
	 * Enables the stubs for remote objects to throw a {@link RemoteException} when
	 * their {@link RMIHandler} will be disposed
	 * 
	 * @param enable set this to true to enable the exception, set to false
	 *               otherwise
	 */
	public void enableRemoteException(boolean enable) {
		this.remoteExceptionEnabled = enable;
	}

	/**
	 * Gets the enable status of the {@link RemoteException}. If the
	 * {@link RemoteException} is disabled, the stubs connected to a failed handler
	 * will return from their invocation the default value for the return type (e.g.
	 * null for objects, false for boolean, 0 for primitive numeric types, etc.)
	 * 
	 * @return true if {@link RemoteException} is enabled, false otherwise
	 */
	public boolean isRemoteExceptionEnabled() {
		return remoteExceptionEnabled;
	}

	/**
	 * Publish the given object respect to the specified interface.
	 * 
	 * @param name   the identifier to use for this service
	 * @param object the implementation of the service to publish
	 * @throws IllegalArgumentException if the specified identifier was already
	 *                                  bound or if the objectId parameter matches
	 *                                  the automatic referencing objectId pattern
	 *                                  that is /\#[0-9]+/
	 */
	public void publish(String name, Object object) {
		try {
			executorService.submit(() -> {
				synchronized (skeletonById) {
					if (name.startsWith(Skeleton.IDENTIFIER_PREFIX))
						throw new IllegalArgumentException("The used identifier prefix '" + Skeleton.IDENTIFIER_PREFIX
								+ "' is reserved to atomatic referencing. Please use another identifier pattern.");

					Skeleton sk = null;
					if (skeletonByObject.containsKey(object)) {
						sk = skeletonByObject.get(object);

						if (skeletonById.containsKey(name) && skeletonById.get(name) != sk)
							throw new IllegalArgumentException(
									"the given object name '" + name + "' is already bound.");

						if (sk.getObject() != object)
							throw new IllegalStateException(
									"INTERNAL ERROR: the given object is associated to a skeleton that does not references it");
					} else {
						if (skeletonById.containsKey(name))
							throw new IllegalArgumentException(
									"the given object name '" + name + "' is already bound.");
						sk = new Skeleton(object, this);
						skeletonById.put(sk.getId(), sk);
						skeletonByObject.put(object, sk);
					}
					skeletonById.put(name, sk);
					sk.addNames(name);
				}
			}).get();
		} catch (InterruptedException e) {
			e.printStackTrace();
		} catch (ExecutionException e) {
			throw (RuntimeException) e.getCause();
		}
	}

	/**
	 * Publish the given object respect to the specified interface. The identifier
	 * is automatically generated and returnedCondition
	 * 
	 * @param object the implementation of the service to publish
	 * @return the generated identifier
	 */
	public String publish(Object object) {
		try {
			return executorService.submit(() -> {
				synchronized (skeletonById) {
					if (skeletonByObject.containsKey(object)) {
						Skeleton sk = skeletonByObject.get(object);
						if (sk.getObject() != object)
							throw new IllegalStateException(
									"the given object is associated to a skeleton that does not references it");
						return sk.getId();
					} else {
						Skeleton sk = new Skeleton(object, this);
						skeletonById.put(sk.getId(), sk);
						skeletonByObject.put(object, sk);
						return sk.getId();
					}
				}
			}).get();
		} catch (InterruptedException e) {
			e.printStackTrace();
		} catch (ExecutionException e) {
			throw (RuntimeException) e.getCause();
		}
		return null;
	}

	/**
	 * Unpublish an object respect to the given interface
	 * 
	 * @param object the object to unpublish
	 */
	public void unpublish(Object object) {
		synchronized (skeletonById) {
			Skeleton skeleton = skeletonByObject.remove(object);
			if (skeleton != null) {
				skeletonById.remove(skeleton.getId());
				for (String id : skeleton.names())
					skeletonById.remove(id);
			}
		}
	}

	/**
	 * Attach a {@link RMIFaultHandler} object
	 * 
	 * @param o the fault handler
	 */
	public void attachFaultHandler(RMIFaultHandler o) {
		synchronized (rMIFaultHandlers) {
			rMIFaultHandlers.add(o);
		}
	}

	/**
	 * Detach a {@link RMIFaultHandler} object
	 * 
	 * @param o the fault handler
	 */
	public void detachFailureHandler(RMIFaultHandler o) {
		synchronized (rMIFaultHandlers) {
			rMIFaultHandlers.remove(o);
		}
	}

	/**
	 * Gets a published remote object by identifier
	 * 
	 * @param objectId object identifier
	 * @return the remotized object
	 */
	public Object getRemoteObject(String objectId) {
		synchronized (skeletonById) {
			Skeleton skeleton = skeletonById.get(objectId);
			if (skeleton != null)
				return skeleton.getObject();
			else
				return null;
		}
	}

	/**
	 * Gets the identifier of a remote object published on this registry
	 * 
	 * @param object the published object reference
	 * @return the associated identifier or null if no entry was found
	 */
	public String getRemoteObjectId(Object object) {
		synchronized (lock) {
			Skeleton skeleton = skeletonByObject.get(object);
			if (skeleton != null)
				return skeleton.getId();
			else
				return null;
		}
	}

	/**
	 * Marks an interface to automatically create remote references for objects that
	 * are sent as arguments for an invocation or as return values. The objects are
	 * automatically published on this registry when the related parameter of the
	 * stub method has a type that matches with the marked interface
	 * 
	 * @param remoteIf the interface to mark
	 */
	public void exportInterface(Class<?> remoteIf) {
		synchronized (remotes) {
			if (remoteIf == Remote.class)
				throw new IllegalArgumentException("agilermi.Remote interface cannot be exported!");
			if (!remoteIf.isInterface())
				throw new IllegalArgumentException("the specified class is not an interface");
			remotes.add(remoteIf);
		}
	}

	/**
	 * Remove a automatic referencing mark for the interface. See the
	 * {@link RMIRegistry#exportInterface(Class)} method. All the objects
	 * automatically referenced until this call, remains published in the registry.
	 * 
	 * @param remoteIf the interface to unmark
	 */
	public void unexportInterface(Class<?> remoteIf) {
		synchronized (remotes) {
			if (Remote.class.isAssignableFrom(remoteIf))
				throw new IllegalArgumentException(
						"An interface that is statically defined as remote cannot be unexported.");
			remotes.remove(remoteIf);
		}
	}

	/**
	 * Check if a class is marked for automatic referencing. A concrete or abstract
	 * class is never remote. An interface is remote if it, directly or indirectly,
	 * extends the {@link Remote} interface or if it was exported or if it extends,
	 * directly or indirectly, an interface that was exported on this registry to be
	 * remote.<br>
	 * See the {@link RMIRegistry#exportInterface(Class)} method.
	 * 
	 * @param remoteIf the interface to check
	 * @return true if the interface is marked for automatic referencing, false
	 *         otherwise
	 */
	public boolean isRemote(Class<?> remoteIf) {
		if (!remoteIf.isInterface() || remoteIf == Remote.class)
			return false;

		// is it statically marked as remote?
		if (Remote.class.isAssignableFrom(remoteIf))
			return true;

		boolean isMapped;
		synchronized (remotes) {
			isMapped = remotes.contains(remoteIf);
		}

		if (!isMapped) {
			for (Class<?> superIf : remoteIf.getInterfaces()) {
				isMapped = isRemote(superIf);
				if (isMapped)
					return true;
			}
		}
		return isMapped;
	}

	/**
	 * Gets the remote interfaces implemented by the class of the remote object
	 * associated to the specified object identifier
	 * 
	 * @param objectId the object identifier
	 * @return the remote interfaces of the remote object
	 */
	public List<Class<?>> getRemoteInterfaces(String objectId) {
		Object object = getRemoteObject(objectId);
		return getRemoteInterfaces(object);
	}

	/**
	 * Gets the remote interfaces implemented by the class of the specified object
	 * 
	 * @param object the object
	 * @return the remote interfaces of the object
	 */
	public List<Class<?>> getRemoteInterfaces(Object object) {
		if (object == null)
			return new ArrayList<>();
		return getRemoteInterfaces(object.getClass());
	}

	/**
	 * Gets the remote interfaces implemented by the specified class and its
	 * super-classes
	 * 
	 * @param cls the class
	 * @return the remote interfaces implemented by the class
	 */
	public List<Class<?>> getRemoteInterfaces(Class<?> cls) {
		List<Class<?>> remoteIfs = new ArrayList<>();
		Class<?> current = cls;
		while (current != null) {
			Class<?>[] ifaces = current.getInterfaces();
			for (Class<?> iface : ifaces) {
				if (isRemote(iface))
					remoteIfs.add(iface);
				else {
					List<Class<?>> remoteSupers = getRemoteInterfaces(iface);
					remoteIfs.addAll(remoteSupers);
				}
			}
			current = current.getSuperclass();
		}
		return remoteIfs;
	}

	/**
	 * Package-scoped. Operation used to broadcast a {@link RMIHandler} failure to
	 * the failure observers attached to this registry
	 * 
	 * @param rMIHandler the object peer that caused the failure
	 * @param exception  the exception thrown by the object peer
	 */
	void sendRmiHandlerFailure(RMIHandler rMIHandler, Exception exception) {
		synchronized (rMIFaultHandlers) {
			rMIFaultHandlers.forEach(o -> {
				try {
					o.onFault(rMIHandler, exception);
				} catch (Throwable e) {
					e.printStackTrace();
				}
			});
		}
	}

	/**
	 * Package-scoped. Get the skeleton associated to the specified object
	 * identifier (server side)
	 * 
	 * @param id the object identifier
	 * @return the skeleton of the remote object
	 */
	Skeleton getSkeleton(String id) {
		synchronized (skeletonById) {
			return skeletonById.get(id);
		}
	}

	/**
	 * Package-scoped. Get the skeleton associated to the specified remote object
	 * (server side)
	 * 
	 * @param objec the remote object
	 * @return the skeleton of the remote object
	 */
	Skeleton getSkeleton(Object object) {
		synchronized (skeletonById) {
			return skeletonByObject.get(object);
		}
	}

}