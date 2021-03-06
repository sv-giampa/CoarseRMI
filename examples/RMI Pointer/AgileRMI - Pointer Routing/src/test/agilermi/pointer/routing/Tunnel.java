package test.agilermi.pointer.routing;

import java.io.IOException;

import agilermi.core.RMIRegistry;

public class Tunnel {

	private static class MyService implements Service {
		Service service;

		@Override
		public Service getService() {
			return service;
		}

		@Override
		public void setService(Service service) {
			this.service = service;
		}

		@Override
		public void useThisService() {
			System.out.println(System.currentTimeMillis() + ": tunnel service invoked!");
		}
	}

	public static void main(String[] args) throws IOException, InterruptedException {
		RMIRegistry rmiRegistry = RMIRegistry.builder().build();
		rmiRegistry.publish("tunnel", new MyService());
		rmiRegistry.enableListener(3333, false);
	}
}
