package dev.rodolphe.accesscontrol.auth;
import dev.rodolphe.accesscontrol.users.UserDto;


record LoginResponse(String token, UserDto user) {
}
