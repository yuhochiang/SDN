from mininet.topo import Topo

class Project1_Topo_309551032( Topo ):
	def __init__( self ):
		Topo.__init__( self )

		# Add hosts
		h1 = self.addHost( 'h1' )
		h2 = self.addHost( 'h2' )
		h3 = self.addHost( 'h3' )
		h4 = self.addHost( 'h4' )

		# Add switches
		s1 = self.addSwitch( 's1' )
		s2 = self.addSwitch( 's2' )
		s3 = self.addSwitch( 's3' )
		s4 = self.addSwitch( 's4' )

		# Add links
		self.addLink( h1, s1)
		self.addLink( h2, s1)
		self.addLink( h3, s2)
		self.addLink( h4, s2)
		self.addLink( s1, s3)
		self.addLink( s1, s4)
		self.addLink( s2, s3)
		self.addLink( s2, s4)

topos = {'topo_309551032': ( lambda : Project1_Topo_309551032() ) }
