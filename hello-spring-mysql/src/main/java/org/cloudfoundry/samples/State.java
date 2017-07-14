package org.cloudfoundry.samples;

public class State {

  private Long id;
  private String stateCode;
  private String name;

  public Long getId() {
    return this.id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getStateCode() {
    return this.stateCode;
  }

  public void setStateCode(String stateCode) {
    this.stateCode = stateCode;
  }

  public String getName() {
    return this.name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String toString() {
    return "State [id=" + this.id + ", stateCode=" + this.stateCode + ", name=" + this.name + "]";
  }
}
