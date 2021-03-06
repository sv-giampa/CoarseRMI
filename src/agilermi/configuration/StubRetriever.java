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

package agilermi.configuration;

import java.io.IOException;
import java.net.UnknownHostException;

import agilermi.core.RMIRegistry;

/**
 * This interface defines objects that are useful to get RMI stubs. This
 * interface should not be implemented by the developer. The developer should
 * obtain instances of this interface through the
 * {@link RMIRegistry#getStubRetriever()} method. Instances of this interface
 * are usually connected to a {@link RMIRegistry} instance that they use to
 * retrieve stubs. Instances of this interface can be sent over RMI and they are
 * always replaced with the instance connected to the local {@link RMIRegistry}
 * on the receiver machine.
 * 
 * @author Salvatore Giampa'
 *
 */
public interface StubRetriever {

	/**
	 * Same of the {@link RMIRegistry#getStub(String, int, String)} method.
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
	Object getStub(String address, int port, String objectId) throws IOException, InterruptedException;

	/**
	 * Same of the {@link RMIRegistry#getStub(String, int, String, Class...)}
	 * method.
	 * 
	 * @param address        the host address
	 * @param port           the host port
	 * @param objectId       the remote object identifier
	 * @param stubInterfaces the interfaces implemented by the stub
	 * @return the stub object
	 * @throws UnknownHostException if the host address cannot be resolved
	 * @throws IOException          if I/O errors occur
	 */
	Object getStub(String address, int port, String objectId, Class<?>... stubInterfaces) throws IOException;
}
