package dk.madpakke.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GeocodingServiceTest {

    @Test
    void stripsTrailingFloorAndDoor() {
        assertEquals("Scharlingevej 21", GeocodingService.stripTrailingFloor("Scharlingevej 21, 3 th"));
        assertEquals("Scharlingevej 21", GeocodingService.stripTrailingFloor("Scharlingevej 21 3 th"));
        assertEquals("Blågårdsgade 13", GeocodingService.stripTrailingFloor("Blågårdsgade 13 4tv"));
        assertEquals("Nørrebrogade 20", GeocodingService.stripTrailingFloor("Nørrebrogade 20, st."));
        assertEquals("Bogensegade 2", GeocodingService.stripTrailingFloor("Bogensegade 2, 3, th"));
    }

    @Test
    void leavesHouseNumbersAndCitiesAlone() {
        assertEquals("Amagerbrogade 5", GeocodingService.stripTrailingFloor("Amagerbrogade 5"));
        assertEquals("Vesterbrogade 100, 1620 København V", GeocodingService.stripTrailingFloor("Vesterbrogade 100, 1620 København V"));
        assertEquals("Søborg Hovedgade 13A", GeocodingService.stripTrailingFloor("Søborg Hovedgade 13A"));
        assertEquals("Jyllingevej 261", GeocodingService.stripTrailingFloor("Jyllingevej 261"));
    }

    @Test
    void neverReturnsAnEmptyAddress() {
        assertEquals("st", GeocodingService.stripTrailingFloor("st"));
    }
}
