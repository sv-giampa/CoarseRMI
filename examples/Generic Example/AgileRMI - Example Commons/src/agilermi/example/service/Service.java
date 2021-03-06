package agilermi.example.service;

import java.util.List;

import agilermi.configuration.Remote;
import agilermi.configuration.annotation.RMIAsynch;
import agilermi.configuration.annotation.RMIRemoteExceptionAlternative;
import agilermi.exception.RemoteException;

public interface Service extends Remote {

	int square(int x) throws RemoteException;

	double add(double x, double y) throws RemoteException;

	@RMIAsynch
	void printlnOnServer(String message) throws RemoteException;

	void startObserversCalls() throws RemoteException;

	void attachObserver(ServiceObserver o) throws RemoteException;

	void detachObserver(ServiceObserver o) throws RemoteException;

	Service getThis() throws RemoteException;

	void infiniteCycle() throws RemoteException, InterruptedException;

	RetrieverContainerExt compute(RetrieverContainerExt classB) throws RemoteException;

	@RMIRemoteExceptionAlternative(IllegalStateException.class)
	void anotherRemoteException() throws RemoteException;

	List<Service> listOfRemoteObjects() throws RemoteException;

}
