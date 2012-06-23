/**
 * Copyright (C) 2009-2012 enStratus Networks Inc
 *
 * ====================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ====================================================================
 */

package org.dasein.cloud.aws.network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

import org.apache.log4j.Logger;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.InternalException;
import org.dasein.cloud.OperationNotSupportedException;
import org.dasein.cloud.ProviderContext;
import org.dasein.cloud.aws.AWSCloud;
import org.dasein.cloud.aws.compute.EC2Exception;
import org.dasein.cloud.aws.compute.EC2Method;
import org.dasein.cloud.identity.ServiceAction;
import org.dasein.cloud.network.AddressType;
import org.dasein.cloud.network.IpAddress;
import org.dasein.cloud.network.IpAddressSupport;
import org.dasein.cloud.network.IpForwardingRule;
import org.dasein.cloud.network.Protocol;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class ElasticIP implements IpAddressSupport {
	static private final Logger logger = AWSCloud.getLogger(ElasticIP.class);

	private AWSCloud provider = null;
	
	ElasticIP(AWSCloud provider) {
		this.provider = provider;
	}
	
	@Override
	public void assign(@Nonnull String addressId, @Nonnull String instanceId) throws InternalException,	CloudException {
		Map<String,String> parameters = provider.getStandardParameters(provider.getContext(), EC2Method.ASSOCIATE_ADDRESS);
		EC2Method method;
		NodeList blocks;
		Document doc;
		
		parameters.put("PublicIp", addressId);
		parameters.put("InstanceId", instanceId);
		method = new EC2Method(provider, provider.getEc2Url(), parameters);
        try {
        	doc = method.invoke();
        }
        catch( EC2Exception e ) {
        	logger.error(e.getSummary());
        	throw new CloudException(e);
        }
        blocks = doc.getElementsByTagName("return");
        if( blocks.getLength() > 0 ) {
        	if( !blocks.item(0).getFirstChild().getNodeValue().equalsIgnoreCase("true") ) {
        		throw new CloudException("Association of address denied.");
        	}
        }
	}

	@Override
    public @Nonnull String forward(@Nonnull String addressId, int publicPort, @Nonnull Protocol protocol, int privatePort, @Nonnull String serverId) throws InternalException, CloudException {
        throw new OperationNotSupportedException();
    }

	@Override
	public @Nullable IpAddress getIpAddress(@Nonnull String addressId) throws InternalException, CloudException {
        ProviderContext ctx = provider.getContext();
        
        if( ctx == null ) {
            throw new CloudException("No context was set for this request");
        }
		Map<String,String> parameters = provider.getStandardParameters(provider.getContext(), EC2Method.DESCRIBE_ADDRESSES);
		IpAddress address = null;
		EC2Method method;
        NodeList blocks;
		Document doc;

		parameters.put("PublicIp.1", addressId);
		method = new EC2Method(provider, provider.getEc2Url(), parameters);
        try {
        	doc = method.invoke();
        }
        catch( EC2Exception e ) {
            String code = e.getCode();
            
            if( code != null && code.equals("InvalidAddress.NotFound") || e.getMessage().contains("Invalid value") ) {
                return null;
            }
        	logger.error(e.getSummary());
        	throw new CloudException(e);
        }
        blocks = doc.getElementsByTagName("addressesSet");
        for( int i=0; i<blocks.getLength(); i++ ) {
        	NodeList items = blocks.item(i).getChildNodes();
        	
            for( int j=0; j<items.getLength(); j++ ) {
            	Node item = items.item(j);
            	
            	if( item.getNodeName().equals("item") ) {
            		address = toAddress(ctx, item);
            		if( address != null && addressId.equals(address.getProviderIpAddressId())) {
            			return address;
            		}
            	}
            }
        }
        return address;
	}

	@Override
	public @Nonnull String getProviderTermForIpAddress(@Nonnull Locale locale) {
		return "elastic IP";
	}

	@Override
	public boolean isAssigned(@Nonnull AddressType type) {
		return type.equals(AddressType.PUBLIC);
	}

	@Override
	public boolean isForwarding() {
		return false;
	}

	@Override
    public boolean isRequestable(@Nonnull AddressType type) {
        return type.equals(AddressType.PUBLIC);
    }
	
    @Override
    public boolean isSubscribed() throws CloudException, InternalException {
        return true;
    }
    
	@Override
	public @Nonnull Iterable<IpAddress> listPrivateIpPool(boolean unassignedOnly) throws InternalException, CloudException {
	    return Collections.emptyList();
	}
	
	@Override
	public @Nonnull Iterable<IpAddress> listPublicIpPool(boolean unassignedOnly) throws InternalException, CloudException {
        ProviderContext ctx = provider.getContext();

        if( ctx == null ) {
            throw new CloudException("No context was set for this request");
        }
    	Map<String,String> parameters = provider.getStandardParameters(provider.getContext(), EC2Method.DESCRIBE_ADDRESSES);
    	ArrayList<IpAddress> list = new ArrayList<IpAddress>();
    	EC2Method method;
        NodeList blocks;
    	Document doc;
    
    	method = new EC2Method(provider, provider.getEc2Url(), parameters);
        try {
        	doc = method.invoke();
        }
        catch( EC2Exception e ) {
        	logger.error(e.getSummary());
        	throw new CloudException(e);
        }
        blocks = doc.getElementsByTagName("addressesSet");
        for( int i=0; i<blocks.getLength(); i++ ) {
        	NodeList items = blocks.item(i).getChildNodes();
        	
            for( int j=0; j<items.getLength(); j++ ) {
            	Node item = items.item(j);
            	
            	if( item.getNodeName().equals("item") ) {
            		IpAddress address = toAddress(ctx, item);
            		
                    if( address != null && (!unassignedOnly || (address.getServerId() == null && address.getProviderLoadBalancerId() == null)) ) {
            			list.add(address);
            		}
            	}
            }
        }
        return list;
    }

	@Override
	public @Nonnull Collection<IpForwardingRule> listRules(@Nonnull String addressId)	throws InternalException, CloudException {
		return new ArrayList<IpForwardingRule>();
	}

    @Override
    public @Nonnull String[] mapServiceAction(@Nonnull ServiceAction action) {
        if( action.equals(IpAddressSupport.ANY) ) {
            return new String[] { EC2Method.EC2_PREFIX + "*" };
        }
        else if( action.equals(IpAddressSupport.ASSIGN) ) {
            return new String[] { EC2Method.EC2_PREFIX + EC2Method.ASSOCIATE_ADDRESS };
        }
        else if( action.equals(IpAddressSupport.CREATE_IP_ADDRESS) ) {
            return new String[] { EC2Method.EC2_PREFIX + EC2Method.ALLOCATE_ADDRESS };
        }
        else if( action.equals(IpAddressSupport.FORWARD) ) {
            return new String[0];
        }
        else if( action.equals(IpAddressSupport.GET_IP_ADDRESS) ) {

            return new String[] { EC2Method.EC2_PREFIX + EC2Method.DESCRIBE_ADDRESSES };
        }
        else if( action.equals(IpAddressSupport.LIST_IP_ADDRESS) ) {

            return new String[] { EC2Method.EC2_PREFIX + EC2Method.DESCRIBE_ADDRESSES };            
        }
        else if( action.equals(IpAddressSupport.RELEASE) ) {
            return new String[] { EC2Method.EC2_PREFIX + EC2Method.DISASSOCIATE_ADDRESS };
        }
        else if( action.equals(IpAddressSupport.REMOVE_IP_ADDRESS) ) {
            return new String[] { EC2Method.EC2_PREFIX + EC2Method.RELEASE_ADDRESS };
        }
        else if( action.equals(IpAddressSupport.STOP_FORWARD) ) {
            return new String[0];
        }
        return new String[0];
    }

	@Override
	public void releaseFromServer(@Nonnull String addressId) throws InternalException, CloudException {
		Map<String,String> parameters = provider.getStandardParameters(provider.getContext(), EC2Method.DISASSOCIATE_ADDRESS);
		EC2Method method;
		NodeList blocks;
		Document doc;
		
		parameters.put("PublicIp", addressId);
		method = new EC2Method(provider, provider.getEc2Url(), parameters);
        try {
        	doc = method.invoke();
        }
        catch( EC2Exception e ) {
        	logger.error(e.getSummary());
        	throw new CloudException(e);
        }
        blocks = doc.getElementsByTagName("return");
        if( blocks.getLength() > 0 ) {
        	if( !blocks.item(0).getFirstChild().getNodeValue().equalsIgnoreCase("true") ) {
        		throw new CloudException("Release of address denied.");
        	}
        }
	}
	
   @Override
    public void releaseFromPool(@Nonnull String addressId) throws InternalException, CloudException {
        Map<String,String> parameters = provider.getStandardParameters(provider.getContext(), EC2Method.RELEASE_ADDRESS);
        EC2Method method;
        NodeList blocks;
        Document doc;
        
        parameters.put("PublicIp", addressId);
        method = new EC2Method(provider, provider.getEc2Url(), parameters);
        try {
            doc = method.invoke();
        }
        catch( EC2Exception e ) {
            logger.error(e.getSummary());
            throw new CloudException(e);
        }
        blocks = doc.getElementsByTagName("return");
        if( blocks.getLength() > 0 ) {
            if( !blocks.item(0).getFirstChild().getNodeValue().equalsIgnoreCase("true") ) {
                throw new CloudException("Deletion of address denied.");
            }
        }
    }

   @Override
    public @Nonnull String request(@Nonnull AddressType betterBePublic) throws InternalException, CloudException {
        if( !betterBePublic.equals(AddressType.PUBLIC) ) {
            throw new OperationNotSupportedException("AWS supports only public IP address requests.");
        }
        Map<String,String> parameters = provider.getStandardParameters(provider.getContext(), EC2Method.ALLOCATE_ADDRESS);
        EC2Method method;
        NodeList blocks;
        Document doc;
        
        method = new EC2Method(provider, provider.getEc2Url(), parameters);
        try {
            doc = method.invoke();
        }
        catch( EC2Exception e ) {
            logger.error(e.getSummary());
            throw new CloudException(e);
        }
        blocks = doc.getElementsByTagName("publicIp");
        if( blocks.getLength() > 0 ) {
            return blocks.item(0).getFirstChild().getNodeValue().trim();
        }
        throw new CloudException("Unable to create an address.");
    }
	   
	@Override
    public void stopForward(@Nonnull String ruleId) throws InternalException, CloudException {
        throw new OperationNotSupportedException();
    }
	
	private @Nullable IpAddress toAddress(@Nonnull ProviderContext ctx, @Nullable Node node) throws CloudException {
        if( node == null ) {
            return null;
        }
        String regionId = ctx.getRegionId();
        
        if( regionId == null ) {
            throw new CloudException("No regionID was set in context");
        }
		NodeList attrs = node.getChildNodes();
		IpAddress address = new IpAddress();
		String instanceId = null,ip = null;

		for( int i=0; i<attrs.getLength(); i++ ) {
			Node attr = attrs.item(i);
			String name;
			
			name = attr.getNodeName();
			if( name.equals("publicIp") ) {
				ip = attr.getFirstChild().getNodeValue().trim();
			}
			else if( name.equals("instanceId") ) {
				if( attr.getChildNodes().getLength() > 0 ) {
					Node id = attr.getFirstChild();
					
					if( id != null ) {
						String value = id.getNodeValue();
						
						if( value != null ) {
							value = value.trim();
							if( value.length() > 0 ) {
								instanceId = value;
							}
						}
					}
				}
			}
		}
		if( ip == null ) {
			throw new CloudException("Invalid address data, no IP.");
		}
		address.setAddressType(AddressType.PUBLIC);
		address.setAddress(ip);
		address.setIpAddressId(ip);
		address.setRegionId(regionId);
        if( instanceId != null && !provider.isAmazon() ) {
            if( instanceId.startsWith("available") ) {
                instanceId = null;
            }
            else {
                int idx = instanceId.indexOf(' ');
                
                if( idx > 0 ) {
                    instanceId = instanceId.substring(0,idx);
                }
            }
		}
		address.setServerId(instanceId);
		return address;
	}
}
