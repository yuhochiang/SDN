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
package nctu.winlab.bridge;

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
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.core.CoreService;
import org.onosproject.core.ApplicationId;
import org.onlab.packet.MacAddress;
import org.onlab.packet.Ethernet;

import java.util.Dictionary;
import java.util.Properties;
import java.util.Map;
import java.util.HashMap;

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

	/** Some configurable property. */
	private String someProperty;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected ComponentConfigService cfgService;

	// to handle packet
	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected PacketService packetService;

	// to install flow rules
	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected FlowObjectiveService flowObjectiveService;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected CoreService coreService;
	
	private ApplicationId appId;
	private LearningBridgeProcessor processor = new LearningBridgeProcessor();

	// MAC address table
	protected Map<DeviceId, Map<MacAddress, PortNumber>> MACTable = new HashMap<>();


	@Activate
	protected void activate() {
		cfgService.registerProperties(getClass());
		log.info("Started");

		appId = coreService.registerApplication("nctu.winlab.bridge");
		// create processor
		packetService.addProcessor(processor, PacketProcessor.director(2));

		// request packet, only ARP and IPv4
		packetService.requestPackets(DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_ARP).build(),
								PacketPriority.REACTIVE, appId);
		packetService.requestPackets(DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4).build(),
								PacketPriority.REACTIVE, appId);

	}

	@Deactivate
	protected void deactivate() {
		cfgService.unregisterProperties(getClass(), false);
		
		// cancel rquest for packet-in
		packetService.cancelPackets(DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_ARP).build(),
								PacketPriority.REACTIVE, appId);
		packetService.cancelPackets(DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4).build(),
								PacketPriority.REACTIVE, appId);						
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

	private class LearningBridgeProcessor implements PacketProcessor {

		@Override
		public void process(PacketContext context) {
			Ethernet parsedPkt = context.inPacket().parsed();
			// if type is not IPV4 and ARP, then return
			if (parsedPkt.getEtherType() != Ethernet.TYPE_IPV4 && parsedPkt.getEtherType() != Ethernet.TYPE_ARP) {
				return;
			}

			ConnectPoint device = context.inPacket().receivedFrom();
			String deviceId = device.deviceId().toString();			
			// log.info("received from switch {}", device.deviceId().toString());

			// create a new map if device ID is not in the MAC address table
			if (!MACTable.containsKey(device.deviceId())) {
				MACTable.put(device.deviceId(), new HashMap<MacAddress, PortNumber>());
			}

			// update MAC address table
			MacAddress src = parsedPkt.getSourceMAC();
			MacAddress dst = parsedPkt.getDestinationMAC();
			MACTable.get(device.deviceId()).put(src, device.port());
			log.info("Add MAC address ==> switch: {}, MAC: {}, port: {}", deviceId, src.toString(), device.port().toString());

			// table miss -> flood
			if (!MACTable.get(device.deviceId()).containsKey(dst)) {
				// packet out
				context.treatmentBuilder().setOutput(PortNumber.FLOOD);
				context.send();
				log.info("MAC {} is missed on {}! Flood packet!", dst.toString(), deviceId);
			}
			else {
			// table hit -> send to designated port and install flow rule
				PortNumber outPort = MACTable.get(device.deviceId()).get(dst);
				
				// install flow rule
				TrafficSelector selector = DefaultTrafficSelector.builder().matchEthSrc(src).matchEthDst(dst).build();
				TrafficTreatment treatment = DefaultTrafficTreatment.builder().setOutput(outPort).build();
				ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
					.withSelector(selector)
					.withTreatment(treatment)
					.withPriority(20)
					.withFlag(ForwardingObjective.Flag.VERSATILE)
					.fromApp(appId)
					.makeTemporary(20)
					.add();
				flowObjectiveService.forward(device.deviceId(), forwardingObjective);
				log.info("MAC {} is matched on {}! Install flow rule!", dst.toString(), deviceId);

				// packet out
				context.treatmentBuilder().setOutput(outPort);
				context.send();

			}

		}
	}
}
