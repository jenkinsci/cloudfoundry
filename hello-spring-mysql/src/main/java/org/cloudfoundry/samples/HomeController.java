package org.cloudfoundry.samples;

import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class HomeController {

  private static final Logger logger = LoggerFactory.getLogger(HomeController.class);
  @Inject
  private ReferenceDataRepository referenceRepository;

  @RequestMapping(value = {"/"}, method = {org.springframework.web.bind.annotation.RequestMethod.GET})
  public String home(Model model) {
    logger.info("Welcome home!");
    model.addAttribute("dbinfo", this.referenceRepository.getDbInfo());
    model.addAttribute("states", this.referenceRepository.findAll());
    return "home";
  }
}
