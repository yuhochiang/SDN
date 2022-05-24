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
package nctu.winlab.unicastdhcp;

import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_ADDED;
import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_UPDATED;
import static org.onosproject.net.config.basics.SubjectFactories.APP_SUBJECT_FACTORY;

import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
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
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.topology.PathService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.ElementId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.Path;
import org.onosproject.net.Link;
import org.onosproject.core.CoreService;
import org.onosproject.core.ApplicationId;
import org.onlab.packet.MacAddress;
import org.onlab.packet.Ethernet;
import org.onlab.packet.IPv4;

import java.util.Dictionary;
import java.util.Properties;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;


/** Sample Network Configuration Service Application */
@Component(immediate = true)
public class AppComponent {

	private final Logger log = LoggerFactory.getLogger(getClass());
	private final NameConfigListener cfgListener = new NameConfigListener();
	private final ConfigFactory factory =
			new ConfigFactory<ApplicationId, NameConfig>(
					APP_SUBJECT_FACTORY, NameConfig.class, "UnicastDhcpConfig") {
				@Override
				public NameConfig createConfig() {
					return new NameConfig();
				}
			};

	private ApplicationId appId;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected NetworkConfigRegistry cfgService;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected CoreService coreService;
	
	// to handle packet
	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected PacketService packetService;

	// to install flow rules
	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected FlowObjectiveService flowObjectiveService;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected FlowRuleService flowRuleService;

	// to get path
	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected PathService pathService;

	private UnicastDHCPProcessor processor = new UnicastDHCPProcessor();

	private DeviceId DHCPserverDevice ;
	private PortNumber DHCPserverPort ;

	// MAC table
	protected Map<MacAddress, List<Object>> MACTable = new HashMap<>();

	@Activate
	protected void activate() {
		appId = coreService.registerApplication("nctu.winlab.unicastdhcp");
		cfgService.addListener(cfgListener);
		cfgService.registerConfigFactory(factory);
		// create processor
		packetService.addProcessor(processor, PacketProcessor.director(2));
		// request IPv4 UDP packet
		packetService.requestPackets(DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4)
						.matchIPProtocol(IPv4.PROTOCOL_UDP).build(), PacketPriority.REACTIVE, appId);
		log.info("Started");
	}

	@Deactivate
	protected void deactivate() {
		cfgService.removeListener(cfgListener);
		cfgService.unregisterConfigFactory(factory);
		// cancel rquest for packet-in
		packetService.cancelPackets(DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4)
						.matchIPProtocol(IPv4.PROTOCOL_UDP).build(), PacketPriority.REACTIVE, appId);
		// clean flow rules
		flowRuleService.removeFlowRulesById(appId);
		// remove processor
		packetService.removeProcessor(processor);

		log.info("Stopped");
	}

	private class NameConfigListener implements NetworkConfigListener {
		@Override
		public void event(NetworkConfigEvent event) {
			if ((event.type() == CONFIG_ADDED || event.type() == CONFIG_UPDATED)
					&& event.configClass().equals(NameConfig.class)) {
				NameConfig config = cfgService.getConfig(appId, NameConfig.class);
				if (config != null) {
					log.info("DHCP server is at {}", config.name());
					DHCPserverDevice = DeviceId.deviceId(config.name().split("/")[0]);
					DHCPserverPort = PortNumber.portNumber(config.name().split("/")[1]);
				}
			}
		}
	}

	private class UnicastDHCPProcessor implements PacketProcessor {
	@Override
		public void process(PacketContext context) {
			if (context.isHandled()) {
				return;
			}

			Ethernet parsedPkt = context.inPacket().parsed();

			if (parsedPkt.getEtherType() != Ethernet.TYPE_IPV4) {
				return;
			}

			ConnectPoint device = context.inPacket().receivedFrom();
						
			//log.info("packet-in from switch {}", device.deviceId().toString());
			MacAddress src = parsedPkt.getSourceMAC();
			MacAddress dst = parsedPkt.getDestinationMAC();

			// create a new pair if src is not in the MAC table
			if (!MACTable.containsKey(src)) {
				List<Object> list = new ArrayList<>();
				list.add(device.deviceId());
				list.add(device.port());
				MACTable.put(src, list);
				//log.info("Add MAC ==> MAC: {}, switch: {}, port: {}", src.toString(), device.deviceId(), device.port().toString());
			}

			// for DHCP DISCOVER
			if (dst.toString().equals("FF:FF:FF:FF:FF:FF")) {
				//log.info("from host, switch: {}, mac: {}", device.deviceId().toString(), src.toString());
				if (device.deviceId().equals(DHCPserverDevice)) {
					installFlowRule(src, dst, DHCPserverPort, DHCPserverDevice);
					// packet out
					context.treatmentBuilder().setOutput(DHCPserverPort);
					context.send();
				}
				else {
					Set<Path> path  = pathService.getPaths(device.deviceId(), DHCPserverDevice);
				
					PortNumber packetOutPort = path.toArray(new Path[path.size()])[0].links().get(0).src().port();
					for (Path p : path) {
						List<Link> link = p.links();
						for (Link l : link) {
							installFlowRule(src, dst, l.src().port(), l.src().deviceId());
						}
					}
					installFlowRule(src, dst, DHCPserverPort, DHCPserverDevice);
					//log.info("outport = {}", packetOutPort);
					// packet out
					context.treatmentBuilder().setOutput(packetOutPort);
					context.send();
				}
			}
			else {
				// for DHCP OFFER
				//log.info("from DHCP server, switch: {}, mac: {}", device.deviceId().toString(), src.toString());

				DeviceId dstDevice = (DeviceId) MACTable.get(dst).get(0);
				PortNumber dstPort = (PortNumber) MACTable.get(dst).get(1);
				
				if (device.deviceId().equals(dstDevice)) {
					installFlowRule(src, dst, dstPort, dstDevice);
					// packet out
					context.treatmentBuilder().setOutput(dstPort);
					context.send();
				}
				else{
					Set<Path> path  = pathService.getPaths(device.deviceId(), dstDevice);
				
					PortNumber packetOutPort = path.toArray(new Path[path.size()])[0].links().get(0).src().port();

					for (Path p : path) {
						List<Link> link = p.links();
						for (Link l : link) {
							installFlowRule(src, dst, l.src().port(), l.src().deviceId());
						}
					}
					installFlowRule(src, dst, dstPort, dstDevice);
					//log.info("outport = {}", packetOutPort);
					// packet out
					context.treatmentBuilder().setOutput(packetOutPort);
					context.send();
				}			
			}
		}
		private void installFlowRule(MacAddress src, MacAddress dst, PortNumber outPort, DeviceId deviceId) {
			TrafficSelector selector = DefaultTrafficSelector.builder().matchEthSrc(src).matchEthDst(dst).build();
			TrafficTreatment treatment = DefaultTrafficTreatment.builder().setOutput(outPort).build();
			ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
				.withSelector(selector)
				.withTreatment(treatment)
				.withPriority(20)
				.withFlag(ForwardingObjective.Flag.VERSATILE)
				.fromApp(appId)
				.makeTemporary(30)
				.add();
			flowObjectiveService.forward(deviceId, forwardingObjective);
			//log.info("flow rule on {}, src: {}, dst: {}, port:{}", deviceId, src.toString(), dst.toString(), outPort.toString());
		}
	}
}
