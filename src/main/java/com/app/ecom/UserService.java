package com.app.ecom;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;

    public List<User> fetchAllUsers(){
        return userRepository.findAll();
    }

    public List<User> addUser(User user){
        userRepository.save(user);
        return userRepository.findAll();
    }

    public User fetchUser(Long id) {
        return userRepository.findById(id).orElse(null);
    }

    public boolean updateUser(Long id, User updatedUser) {
        return userRepository.findById(id)
                .map(existingUser -> {
                    existingUser.setFirstName(updatedUser.getFirstName());
                    existingUser.setLastName(updatedUser.getLastName());
                    userRepository.save(existingUser);
                    return true;
                }).orElse(false);
    }
}