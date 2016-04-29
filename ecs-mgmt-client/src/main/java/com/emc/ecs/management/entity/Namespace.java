package com.emc.ecs.management.entity;


import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;

@XmlRootElement(name = "namespace")
@XmlType(propOrder = {"name","id", "link"})
public class Namespace {
	
	public final static String ID = "id";
	public final static String LINK = "link";
	public final static String NAME = "name";
	
	private String id;
	private String link;
    private String name;
    
    @XmlElement(name = ID)
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    } 
     
    @XmlElement(name = LINK)
    public String getLink() {
        return link;
    }

    public void setLink(String link) {
        this.link = link;
    } 
    
    @XmlElement(name = NAME)
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((id == null) ? 0 : id.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		Namespace other = (Namespace) obj;
		if (id == null) {
			if (other.id != null)
				return false;
		} else if (!id.equals(other.id))
			return false;
		return true;
	}
    

   
}
