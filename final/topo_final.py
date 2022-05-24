#!/usr/bin/python

from mininet.topo import Topo
from mininet.net import Mininet
from mininet.log import setLogLevel
from mininet.node import RemoteController
from mininet.cli import CLI
from mininet.node import Node
from mininet.link import TCLink

class MyTopo( Topo ):

    def __init__( self ):
        Topo.__init__( self )
	
	# Add hosts
	h1 = self.addHost('h1', ip='10.0.2.1/16', mac='00:00:00:00:00:01')	# dhcp server
	h2 = self.addHost('h2', ip='0.0.0.0', mac='00:00:00:00:00:02')	# new host
	h3 = self.addHost('h3', ip='10.0.2.2/16', mac='00:00:00:00:00:03')
	h4 = self.addHost('h4', ip='10.0.3.1/16', mac='00:00:00:00:00:04')
	h5 = self.addHost('h5', ip='10.0.3.2/16', mac='00:00:00:00:00:05')
		
	# Add switches
	s1 = self.addSwitch('s1')
	s2 = self.addSwitch('s2')
	s3 = self.addSwitch('s3')
       
	# Add links
	self.addLink( s1, s2)
	self.addLink( h1, s2)
	self.addLink( h2, s2)
	self.addLink( h3, s2)
	self.addLink( s1, s3)
	self.addLink( h4, s3)
	self.addLink( h5, s3)

def run():
    topo = MyTopo()
    net = Mininet(topo=topo, controller=None, link=TCLink)
    net.addController('c0', controller=RemoteController, ip='127.0.0.1', port=6653)

    net.start()

    print("[+] Run DHCP server")
    dhcp = net.getNodeByName('h1')
    # dhcp.cmdPrint('service isc-dhcp-server restart &')
    dhcp.cmdPrint('/usr/sbin/dhcpd 4 -pf /run/dhcp-server-dhcpd.pid -cf ./dhcpd.conf %s' % dhcp.defaultIntf())

    CLI(net)
    print("[-] Killing DHCP server")
    dhcp.cmdPrint("kill -9 `ps aux | grep h1-eth0 | grep dhcpd | awk '{print $2}'`")
    net.stop()

if __name__ == '__main__':
    setLogLevel('info')
    run()
