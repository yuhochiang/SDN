/*
 * Copyright 2020-present Open Networking Foundation
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
package nctu.winlab.ProxyArp;

import com.google.common.collect.ImmutableSet;
import org.onosproject.cfg.ComponentConfigService;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.onosproject.net.packet.PacketService;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.OutboundPacket;
import org.onosproject.net.packet.DefaultOutboundPacket;
import org.onosproject.net.host.HostService;
import org.onosproject.net.edge.EdgePortService;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.Host;
import org.onosproject.core.CoreService;
import org.onosproject.core.ApplicationId;
import org.onlab.packet.MacAddress;
import org.onlab.packet.Ethernet;
import org.onlab.packet.ARP;
import org.onlab.packet.Ip4Address;

import java.util.Dictionary;
import java.util.Properties;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.nio.ByteBuffer;

import static org.onlab.util.Tools.get;

/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true,
		   service = {SomeInterface.class},
		   property = {
			   "someProperty=Some Default String Value",
		   })
public class AppComponent implements SomeInterface {

	private final Logger log = LoggerFactory.getLogger(getClass());

	private ApplicationId appId;

	/** Some configurable property. */
	private String someProperty;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected ComponentConfigService cfgService;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected CoreService coreService;

	// to handle packet
	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected PacketService packetService;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected EdgePortService edgePortService;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected HostService hostService;

	private ProxyArpProcessor processor = new ProxyArpProcessor();

	// ARP table, ket: IP, value: MAC
	protected Map<Ip4Address, MacAddress> ArpTable = new HashMap<>();

	@Activate
	protected void activate() {
		appId = coreService.registerApplication("nctu.winlab.ProxyArp");
		cfgService.registerProperties(getClass());
		// create processor
		packetService.addProcessor(processor, PacketProcessor.director(2));
		// request ARP packet
		packetService.requestPackets(DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_ARP).build(), PacketPriority.REACTIVE, appId);
		log.info("Started");
	}

	@Deactivate
	protected void deactivate() {
		cfgService.unregisterProperties(getClass(), false);
		// cancel rquest for packet-in
		packetService.cancelPackets(DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_ARP).build(), PacketPriority.REACTIVE, appId);
		// remove processor
		packetService.removeProcessor(processor);
		log.info("Stopped");
	}

	@Modified
	public void modified(ComponentContext context) {
		Dictionary<?, ?> properties = context != null ? context.getProperties() : new Properties();
		if (context != null) {
			someProperty = get(properties, "someProperty");
		}
		log.info("Reconfigured");
	}

	@Override
	public void someMethod() {
		log.info("Invoked");
	}

	private class ProxyArpProcessor implements PacketProcessor {
	@Override    
		public void process(PacketContext context) {
			if (context.isHandled()) {
				return;
			}

			Ethernet parsedPkt = context.inPacket().parsed();

			if (parsedPkt.getEtherType() != Ethernet.TYPE_ARP) {
				return;
			}

			ConnectPoint device = context.inPacket().receivedFrom();

			ARP arp = (ARP) parsedPkt.getPayload();

			MacAddress senderMAC = MacAddress.valueOf(arp.getSenderHardwareAddress());
			Ip4Address senderIP = Ip4Address.valueOf(arp.getSenderProtocolAddress());
			Ip4Address targetIP = Ip4Address.valueOf(arp.getTargetProtocolAddress());
			log.info("sender: {}, {}, target: {}", senderIP.toString(), senderMAC.toString(), targetIP.toString());


			// update ARP table
			if (!ArpTable.containsKey(senderIP)) {
				ArpTable.put(senderIP, senderMAC);
				log.info("update table: {}, {}", senderIP.toString(), senderMAC.toString());
			}

			// ARP request
			if (arp.getOpCode() == ARP.OP_REQUEST) {
				// mapping doesn't exist -> flood to edge ports
				if (!ArpTable.containsKey(targetIP)) {
					log.info("TABLE MISS. Send request to edge ports");
					for (ConnectPoint p : edgePortService.getEdgePoints()) {
						if ( (!p.deviceId().equals(device.deviceId())) || (!p.port().equals(device.port())) ) {
							log.info("edge:deviceid: {}, port:{}", p.deviceId().toString(), p.port().toString());
							TrafficTreatment treatment = DefaultTrafficTreatment.builder().setOutput(p.port()).build();
							OutboundPacket outPacket = new DefaultOutboundPacket(p.deviceId(), treatment, ByteBuffer.wrap(parsedPkt.serialize()));
							packetService.emit(outPacket);
							//edgePortService.emitPacket(p.deviceId(), ByteBuffer.wrap(parsedPkt.serialize()), treatment);
						}
					}
				}
				else {
				// generate a reply packet
					log.info("TABLE HIT. Requested MAC = {}", ArpTable.get(targetIP).toString());
					MacAddress srcMAC = ArpTable.get(targetIP);

					Ethernet reply = ARP.buildArpReply(targetIp, srcMAC, parsedPkt);
					TrafficTreatment treatment = DefaultTrafficTreatment.builder().setOutput(device.port()).build();
					OutboundPacket outPacket = new DefaultOutboundPacket(device.deviceId(), treatment, ByteBuffer.wrap(reply.serialize()));
					packetService.emit(outPacket);
					log.info("hit:deviceid: {}, port:{}", device.deviceId().toString(), device.port().toString());
				}
			}
			else if (arp.getOpCode() == ARP.OP_REPLY) {
				log.info("RECV REPLY. Requested MAC = {}", senderMAC.toString());
				//byte [] targetMAC = parsedPkt.getPayload().getTargetHardwareAddress();
				Set<Host> host = hostService.getHostsByIp(targetIP);
				for (Host h : host) {
					ConnectPoint cp = h.location();
					TrafficTreatment treatment = DefaultTrafficTreatment.builder().setOutput(cp.port()).build();
					OutboundPacket outPacket = new DefaultOutboundPacket(cp.deviceId(), treatment, ByteBuffer.wrap(parsedPkt.serialize()));
					packetService.emit(outPacket);
					log.info("reply:deviceid: {}, port:{}", cp.deviceId().toString(), cp.port().toString());
				}
				
			}
		}


	}

}
