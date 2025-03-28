package com.example.backend.service.announcment.impl;

import com.example.backend.dto.announcement.AnnouncementCategory;
import com.example.backend.dto.announcement.AnnouncementRequestDTO;
import com.example.backend.dto.announcement.AnnouncementDetailResponseDTO;
import com.example.backend.dto.announcement.AnnouncementResponseDTO;
import com.example.backend.dto.announcement.FileResponseDTO;
import com.example.backend.dto.announcement.ImageResponseDTO;
import com.example.backend.dto.vote.VoteRequestDTO;
import com.example.backend.model.entity.announcement.Announcement;
import com.example.backend.model.entity.announcement.ContestCategory;
import com.example.backend.model.entity.announcement.File;
import com.example.backend.model.entity.announcement.Image;
import com.example.backend.model.entity.member.Member;
import com.example.backend.model.entity.member.UserRole;
import com.example.backend.model.entity.vote.Vote;
import com.example.backend.model.repository.announcement.AnnouncementRepository;
import com.example.backend.model.repository.announcement.ContestCategoryRepository;
import com.example.backend.model.repository.member.MemberRepository;
import com.example.backend.service.announcment.AnnouncementService;
import com.example.backend.service.announcment.FileService;
import com.example.backend.service.announcment.ImageService;
import com.example.backend.service.vote.VoteService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AnnouncementServiceImpl implements AnnouncementService {

    private final AnnouncementRepository announcementRepository;
    private final MemberRepository memberRepository;
    private final FileService fileService;
    private final ImageService imageService;
    private final VoteService voteService;
    private final ContestCategoryRepository contestCategoryRepository;

    @Override
    @Transactional(readOnly = true)
    public List<AnnouncementResponseDTO> getAllAnnouncements(Authentication authentication) {
        return announcementRepository.findAll().stream()
                .filter(announcement -> isWithinTwoYears(announcement.getCreatedTime().getYear()))
                .filter(Announcement::isVisible)
                .sorted(Comparator.comparing(Announcement::getId))
                .map(AnnouncementResponseDTO::toResponseDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public AnnouncementDetailResponseDTO getAnnouncementById(Authentication authentication, Long id)
            throws IOException {
        Announcement announcement = findAnnouncementById(id);
        validateVisibility(announcement);
        List<FileResponseDTO> fileResponseDTOS = new ArrayList<>();
        List<ImageResponseDTO> imageResponseDTOS = new ArrayList<>();

        if(announcement.getFiles() != null) {
            for (File file : announcement.getFiles()) {
                fileResponseDTOS.add(File.toResponseDTO(file,fileService.getFileData(file.getId())));
            }
        }
        if(announcement.getImages() != null) {
            for (Image image : announcement.getImages()) {
                imageResponseDTOS.add(Image.toResponseDTO(image, imageService.getImageData(image.getId())));
            }
        }
        return AnnouncementDetailResponseDTO.toResponseDTO(announcement, fileResponseDTOS, imageResponseDTOS);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AnnouncementResponseDTO> getAnnouncementsByCategory(Authentication authentication, AnnouncementCategory category) {
        return announcementRepository.findByAnnouncementCategory(category).stream()
                .filter(announcement -> isWithinTwoYears(announcement.getCreatedTime().getYear()))
                .filter(Announcement::isVisible)
                .sorted(Comparator.comparing(Announcement::getId))
                .map(AnnouncementResponseDTO::toResponseDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public AnnouncementDetailResponseDTO createAnnouncement(Authentication authentication, AnnouncementRequestDTO announcementRequestDTO) throws IOException {
        Member manager = validateAndGetAdmin(authentication);

        Announcement announcement = announcementRequestDTO.toEntity(manager, new ArrayList<>(), new ArrayList<>(), null);
        Announcement savedAnnouncement = announcementRepository.save(announcement);

        saveFilesAndImages(announcementRequestDTO, savedAnnouncement);

        VoteRequestDTO voteRequestDTO = announcementRequestDTO.getVoteRequest();
        if (voteRequestDTO != null) {
            Vote vote = voteService.createVote(authentication, voteRequestDTO, savedAnnouncement);
            savedAnnouncement.setVotes(List.of(vote));
        }

        savedAnnouncement = announcementRepository.save(savedAnnouncement);

        List<FileResponseDTO> fileResponseDTOS = new ArrayList<>();
        List<ImageResponseDTO> imageResponseDTOS = new ArrayList<>();

        if(savedAnnouncement.getFiles() != null) {
            for (File file : savedAnnouncement.getFiles()) {
                fileResponseDTOS.add(File.toResponseDTO(file, fileService.getFileData(file.getId())));
            }
        }
        if(savedAnnouncement.getImages() != null) {
            for (Image image : savedAnnouncement.getImages()) {
                imageResponseDTOS.add(Image.toResponseDTO(image, imageService.getImageData(image.getId())));
            }
        }

        if (announcementRequestDTO.getAnnouncementCategory().equals(AnnouncementCategory.CONTEST)) {
            String contestCategoryName = extractContestCategoryName(announcementRequestDTO.getAnnouncementTitle());
            if (contestCategoryName == null) {
                throw new IllegalArgumentException("공모전 카테고리의 공지사항 제목은 [공모전]으로 시작해야 합니다.");
            }

            ContestCategory contestCategory = ContestCategory.builder()
                    .contestCategoryName(contestCategoryName)
                    .build();
            contestCategoryRepository.save(contestCategory);
        }

        return AnnouncementDetailResponseDTO.toResponseDTO(savedAnnouncement, fileResponseDTOS, imageResponseDTOS);
    }

    @Override
    @Transactional
    public AnnouncementDetailResponseDTO updateAnnouncement(Authentication authentication, Long id, AnnouncementRequestDTO announcementRequestDTO) throws IOException {
        validateAndGetAdmin(authentication);

        Announcement existingAnnouncement = findAnnouncementById(id);

        List<File> filesToDelete = existingAnnouncement.getFiles().stream()
                .toList();

        List<Image> imagesToDelete = existingAnnouncement.getImages().stream()
                .toList();

        filesToDelete.forEach(file -> {
            try {
                fileService.deleteFileData(file.getId());
                fileService.deleteFile(file.getId());
                existingAnnouncement.getFiles().remove(file);
            } catch (Exception e) {
                throw new RuntimeException("파일 삭제 중 오류 발생: " + file.getId(), e);
            }
        });

        imagesToDelete.forEach(image -> {
            try {
                imageService.deleteImageData(image.getId());
                imageService.deleteImage(image.getId());
                existingAnnouncement.getImages().remove(image);
            } catch (Exception e) {
                throw new RuntimeException("이미지 삭제 중 오류 발생: " + image.getId(), e);
            }
        });

        boolean hasNewFiles = announcementRequestDTO.getNewFiles() != null
                && !announcementRequestDTO.getNewFiles().isEmpty()
                && announcementRequestDTO.getNewFiles().stream().anyMatch(newFile -> !newFile.isEmpty());

        boolean hasNewImages = announcementRequestDTO.getNewImages() != null
                && !announcementRequestDTO.getNewImages().isEmpty()
                && announcementRequestDTO.getNewImages().stream().anyMatch(newImage -> !newImage.isEmpty());

        if (hasNewFiles || hasNewImages) {
            saveFilesAndImages(announcementRequestDTO, existingAnnouncement);
        }

        existingAnnouncement.update(announcementRequestDTO, existingAnnouncement.getFiles(), existingAnnouncement.getImages(), null);
        Announcement updatedAnnouncement = announcementRepository.save(existingAnnouncement);

        List<FileResponseDTO> fileResponseDTOS = updatedAnnouncement.getFiles().stream()
                .map(file -> {
                    try {
                        return FileResponseDTO.builder()
                                .id(file.getId())
                                .originalFilename(file.getOriginalFilename())
                                .fileData(fileService.getFileData(file.getId()))
                                .fileUrl(file.getFileUrl())
                                .build();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .collect(Collectors.toList());

        List<ImageResponseDTO> imageResponseDTOS = updatedAnnouncement.getImages().stream()
                .map(image -> {
                    try {
                        return ImageResponseDTO.builder()
                                .id(image.getId())
                                .imageName(image.getOriginalFilename())
                                .imageData(imageService.getImageData(image.getId()))
                                .imageUrl(image.getImageUrl())
                                .build();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .collect(Collectors.toList());

        if(announcementRequestDTO.getAnnouncementCategory().equals(AnnouncementCategory.CONTEST)){
            String contestCategoryName = extractContestCategoryName(announcementRequestDTO.getAnnouncementTitle());
            if(contestCategoryName == null){
                throw new IllegalArgumentException("공모전 카테고리의 공지사항 제목은 [공모전]으로 시작해야 합니다.");
            }
            String existingContestCategoryName = extractContestCategoryName(existingAnnouncement.getAnnouncementTitle());

            if(!contestCategoryName.equals(existingContestCategoryName)){
                ContestCategory contestCategory = contestCategoryRepository.findByContestCategoryName(existingContestCategoryName);
                contestCategory.setContestCategoryName(contestCategoryName);
                contestCategoryRepository.save(contestCategory);
            }
        }

        return AnnouncementDetailResponseDTO.toResponseDTO(updatedAnnouncement, fileResponseDTOS, imageResponseDTOS);
    }

    @Override
    @Transactional
    public void deleteAnnouncement(Authentication authentication, Long id) {
        Announcement announcement = getAnnouncementEntityById(authentication, id);

        if(announcement.getAnnouncementCategory().equals(AnnouncementCategory.CONTEST)) {
            String contestTitle = extractContestCategoryName(announcement.getAnnouncementTitle());

            ContestCategory contestCategory = contestCategoryRepository.findByContestCategoryName(contestTitle);;
            contestCategoryRepository.delete(contestCategory);
        }
        announcementRepository.delete(announcement);
    }

    @Override
    @Transactional
    public void hideAnnouncement(Authentication authentication, Long id) {
        Announcement announcement = getAnnouncementEntityById(authentication, id);
        announcement.setVisible(false);
        announcementRepository.save(announcement);
    }

    @Override
    @Transactional
    public void showAnnouncement(Authentication authentication, Long id) {
        Announcement announcement = getAnnouncementEntityById(authentication, id);
        announcement.setVisible(true);
        announcementRepository.save(announcement);
    }

    @Override
    @Transactional(readOnly = true)
    public AnnouncementDetailResponseDTO getAnnouncementWithComments(Long announcementId) throws IOException {
        Announcement announcement = findAnnouncementById(announcementId);

        List<FileResponseDTO> fileResponseDTOS = announcement.getFiles().stream()
                .map(file -> FileResponseDTO.builder()
                        .id(file.getId())
                        .originalFilename(file.getOriginalFilename())
                        .fileUrl(file.getFileUrl())
                        .build())
                .collect(Collectors.toList());

        List<ImageResponseDTO> imageResponseDTOS = announcement.getImages().stream()
                .map(image -> ImageResponseDTO.builder()
                        .id(image.getId())
                        .imageName(image.getOriginalFilename())
                        .imageUrl(image.getImageUrl())
                        .build())
                .collect(Collectors.toList());

        return AnnouncementDetailResponseDTO.toResponseDTO(announcement, fileResponseDTOS, imageResponseDTOS);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AnnouncementResponseDTO> getHiddenAnnouncements(Authentication authentication) {
        validateAdmin(authentication);
        return announcementRepository.findAll().stream()
                .filter(announcement -> !announcement.isVisible())
                .sorted(Comparator.comparing(Announcement::getId))
                .map(AnnouncementResponseDTO::toResponseDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<String> getContestCategoryName() {
        return contestCategoryRepository.findAll().stream()
                .map(ContestCategory::getContestCategoryName)
                .collect(Collectors.toList());
    }
    private void saveFilesAndImages(AnnouncementRequestDTO announcementRequestDTO, Announcement announcement) throws IOException {
        List<MultipartFile> newFiles = announcementRequestDTO.getNewFiles();
        List<MultipartFile> newImages = announcementRequestDTO.getNewImages();

        List<File> files= new ArrayList<>();
        List<Image> images=new ArrayList<>();
        if (newFiles != null) {
            for (MultipartFile newFile : newFiles) {
                if (!newFile.isEmpty()) {
                    Long fileId = fileService.saveFile(newFile, announcement);
                    File savedFile = fileService.getFile(fileId);
                    if (announcement.getFiles().stream().noneMatch(file -> file.getId().equals(savedFile.getId()))) {
                        files.add(savedFile);
                    }
                }
            }
            announcement.setFiles(files);
        }

        if (newImages != null) {
            for (MultipartFile newImage : newImages) {
                if (!newImage.isEmpty()) {
                    Long imageId = imageService.saveImage(newImage, announcement);
                    Image savedImage = imageService.getImage(imageId);
                    if (announcement.getImages().stream().noneMatch(image -> image.getId().equals(savedImage.getId()))) {
                        images.add(savedImage);
                    }
                }
            }
            announcement.setImages(images);
        }
        announcementRepository.save(announcement);
    }

    private String extractContestCategoryName(String title) {
        Pattern pattern = Pattern.compile("\\[(.*?)\\]");
        Matcher matcher = pattern.matcher(title);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private boolean isWithinTwoYears(int year) {
        int currentYear = LocalDate.now().getYear();
        return year >= currentYear - 2;
    }

    private Announcement findAnnouncementById(Long id) {
        return announcementRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("해당 ID의 공지사항을 찾을 수 없습니다."));
    }

    private Member validateAndGetAdmin(Authentication authentication) {
        String studentId = authentication.getName();
        Member member = memberRepository.findByStudentId(studentId);
        if (member.getUserRole() != UserRole.ROLE_ADMIN) {
            throw new IllegalArgumentException("관리자만 공지사항을 생성할 수 있습니다.");
        }
        return member;
    }

    private void validateAdmin(Authentication authentication) {
        if (authentication.getAuthorities().stream()
                .noneMatch(grantedAuthority -> grantedAuthority.getAuthority().equals("ROLE_ADMIN"))) {
            throw new IllegalArgumentException("관리자만 숨겨진 공지사항을 조회할 수 있습니다.");
        }
    }

    private Announcement getAnnouncementEntityById(Authentication authentication, Long id) {
        validateAndGetAdmin(authentication);
        return findAnnouncementById(id);
    }

    private void validateVisibility(Announcement announcement) {
        if (!announcement.isVisible()) {
            throw new IllegalArgumentException("해당 공지사항은 숨김 처리되어 조회할 수 없습니다.");
        }
    }
}
